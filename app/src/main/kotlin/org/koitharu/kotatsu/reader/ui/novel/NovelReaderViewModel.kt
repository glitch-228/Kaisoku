/*
 * Novel reader state holder, modeled after the manga ReaderViewModel
 * but much simpler: chapters are loaded on demand, progress stored as
 * within-chapter character ratio in `scroll` (ratio * 10000).
 * Ported in part from Kototoro (Apache-2.0).
 * Copyright 2025 Kototoro contributors.
 * Copyright 2026 Kaisoku contributors.
 */
package org.koitharu.kotatsu.reader.ui.novel

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.lnreader.normalizeNovelChapterOrder
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.HistoryUpdateUseCase
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import android.content.Context
import androidx.core.net.toUri
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import javax.inject.Inject

@HiltViewModel
class NovelReaderViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	savedStateHandle: SavedStateHandle,
	private val dataRepository: MangaDataRepository,
	private val repositoryFactory: MangaRepository.Factory,
	private val historyRepository: HistoryRepository,
	private val historyUpdateUseCase: HistoryUpdateUseCase,
	private val localRepository: LocalMangaRepository,
	private val appSettings: AppSettings,
	private val translator: org.koitharu.kotatsu.reader.translate.MultimodalTranslator,
) : BaseViewModel() {

	private val intent = MangaIntent(savedStateHandle)

	/** Explicitly requested position (e.g. a chapter-row tap), wins over history. */
	private val requestedState: ReaderState? = savedStateHandle[EXTRA_STATE]

	val isIncognitoMode: Boolean = savedStateHandle[EXTRA_INCOGNITO] ?: false

	val manga = MutableStateFlow<Manga?>(null)
	val chapters = MutableStateFlow<List<MangaChapter>>(emptyList())
	val currentChapterIndex = MutableStateFlow(-1)
	val chapterRequest = MutableStateFlow<Pair<Int, Long>?>(null)
	val isUiLoading = MutableStateFlow(false)
	val readerSettings = MutableStateFlow(NovelReaderSettings.load(appContext))
	val initialRatio = MutableStateFlow<Float?>(null)
	val isReadingReversed = MutableStateFlow(false)
	val readingSource = MutableStateFlow<MangaSource?>(null)

	private var lastSavedState: ReaderState? = null
	private var lastSavedRatio = 0f
	private var lastSavedPercent = -1f
	private val translations = object : LinkedHashMap<Long, Pair<String, String>>(8, .75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<String, String>>?) = size > 8
	}

	private fun translationConfig() = listOf(appSettings.translateProvider.name, appSettings.translateEndpoint,
		appSettings.translateModel, appSettings.translateSourceLanguage, appSettings.translateTargetLanguage).joinToString("\n")

	fun isChapterTranslated(index: Int): Boolean {
		val id = chapters.value.getOrNull(index)?.id ?: return false
		return synchronized(translations) { translations[id]?.first == translationConfig() }
	}

	fun showOriginalChapter(index: Int) {
		val id = chapters.value.getOrNull(index)?.id ?: return
		synchronized(translations) { translations.remove(id) }
	}

	suspend fun loadChapterText(index: Int): String? {
		val id = chapters.value.getOrNull(index)?.id ?: return null
		synchronized(translations) {
			translations[id]?.takeIf { it.first == translationConfig() }?.let { return it.second }
		}
		return loadChapterHtml(index)?.let(NovelHtml::decodeChapterHtml)?.let(NovelHtml::toPlainText)
	}

	suspend fun translateChapter(index: Int, onProgress: (Int, Int) -> Unit) {
		val chapter = chapters.value.getOrNull(index) ?: return
		val config = translationConfig()
		val original = loadChapterHtml(index)?.let(NovelHtml::decodeChapterHtml)?.let(NovelHtml::toPlainText)
			?.takeIf(String::isNotBlank) ?: error("Chapter returned no text")
		val translated = translator.translateText(original, onProgress)
		kotlinx.coroutines.currentCoroutineContext().ensureActive()
		if (config != translationConfig()) error("Translation settings changed. Please retry.")
		synchronized(translations) { translations[chapter.id] = config to translated }
	}
	@Volatile var imageHeaders: Map<String, String> = emptyMap()
		private set

	init {
		launchLoadingJob(Dispatchers.Default) {
			var target = dataRepository.resolveIntent(intent, withChapters = true)
				?: error("Cannot resolve novel ${intent.mangaId}")
			if (target.isLocal) target = localRepository.getDetails(target)
			else {
				val saved = localRepository.findSavedManga(target, withDetails = true)?.manga
				val localChapters = saved?.chapters.orEmpty().associateBy { it.id }
				target = target.copy(chapters = target.chapters?.takeIf { it.isNotEmpty() }?.map { localChapters[it.id] ?: it }
					?: saved?.chapters)
			}
			readingSource.value = if (target.isLocal) LocalMangaParser(target.url.toUri()).getMangaInfo()?.source
				else target.source
			// History/favorites may still contain the old newest-first Jaomix snapshot.
			target = target.copy(chapters = target.chapters?.let { list ->
				normalizeNovelChapterOrder(target.source.name, list) { it.title }
			})
			manga.value = target
			chapters.value = target.chapters.orEmpty()
			// The intent/DB snapshot may carry no chapters (cold open from history with a
			// stale row, or a first open straight from search). Refresh details from the
			// plugin so the chapter list is real before we try to render anything.
			if (target.chapters.isNullOrEmpty()) {
				runCatchingCancellable {
					val repository = repositoryFactory.create(target.source)
					val refreshed = repository.getDetails(target)
					manga.value = refreshed
					chapters.value = refreshed.chapters.orEmpty()
					dataRepository.storeManga(refreshed, replaceExisting = false)
				}.onFailure { e ->
					if (e !is CancellationException) {
						errorEvent.call(e)
					}
				}
			}
			val history = historyRepository.getOne(target)
			val requested: ReaderState? = this@NovelReaderViewModel.requestedState?.takeIf { s ->
				chapters.value.any { it.id == s.chapterId }
			}
			val reversed = readingSource.value?.let { SourceSettings(appContext, it).isNovelReadingReversed } ?: false
			val sequence = novelReadingSequence(chapters.value, reversed, requested?.chapterId ?: history?.chapterId)
			isReadingReversed.value = reversed
			chapters.value = sequence.chapters
			val initialChapter = sequence.currentIndex
			initialRatio.value = resolveInitialRatio(requested, history?.takeIf { h ->
				chapters.value.getOrNull(initialChapter)?.id == h.chapterId
			})
			currentChapterIndex.value = initialChapter
			chapterRequest.value = initialChapter to 0L
		}
	}

	private fun resolveInitialRatio(requestedState: ReaderState?, history: MangaHistory?): Float? {
		val scroll = requestedState?.scroll ?: history?.scroll ?: 0
		if (scroll <= 0) return null
		return novelProgressRatio(scroll)
	}

	companion object {

		/** Extras shared with [ReaderIntent] (same key strings). */
		const val EXTRA_STATE = ReaderIntent.EXTRA_STATE
		const val EXTRA_INCOGNITO = ReaderIntent.EXTRA_INCOGNITO

	}

	/** Persist the current reading position. Ratio: 0..1 within the chapter. */
	fun saveProgress(chapterIndex: Int, ratio: Float) {
		val target = manga.value ?: return
		val chapter = chapters.value.getOrNull(chapterIndex) ?: return
		val total = chapters.value.size
		val state = novelHistoryPosition(chapter.id, ratio)
		lastSavedRatio = novelProgressRatio(state.scroll)
		val percent = if (total > 0) (chapterIndex + lastSavedRatio) / total else 0f
		if (lastSavedState == state && lastSavedPercent == percent) return
		lastSavedState = state
		lastSavedPercent = percent
		if (!isIncognitoMode) historyUpdateUseCase.invokeAsync(target, state, percent.coerceIn(0f, 1f))
	}

	/**
	 * Ratio to restore for [chapterIndex]: an explicitly requested position wins
	 * (chapter navigation), otherwise the live position of the current chapter is
	 * reused so activity recreation does not jump back to the chapter start.
	 */
	fun restoreRatioFor(chapterIndex: Int): Float? {
		val explicit = initialRatio.value
		if (explicit != null) {
			initialRatio.value = null
			return explicit
		}
		if (chapters.value.getOrNull(chapterIndex)?.id == lastSavedState?.chapterId) {
			return lastSavedRatio
		}
		return null
	}

	fun switchChapter(index: Int) {
		if (index in chapters.value.indices) {
			currentChapterIndex.value = index
		}
	}

	fun navigateTo(index: Int, ratio: Float) {
		if (index !in chapters.value.indices) return
		initialRatio.value = ratio
		switchChapter(index)
		chapterRequest.value = index to ((chapterRequest.value?.second ?: 0L) + 1L)
	}

	fun setReadingReversed(reversed: Boolean, visibleIndex: Int?, ratio: Float?) {
		if (reversed == isReadingReversed.value) return
		val index = visibleIndex ?: currentChapterIndex.value
		val chapter = chapters.value.getOrNull(index) ?: return
		val position = ratio ?: restoreRatioFor(index) ?: 0f
		val sequence = novelReadingSequence(chapters.value, reversed = true, currentChapterId = chapter.id)
		isReadingReversed.value = reversed
		chapters.value = sequence.chapters
		navigateTo(sequence.currentIndex, position)
	}

	fun configuredReadingReversed(): Boolean? {
		// Initial loading applies the preference after it has resolved the complete sequence.
		if (chapterRequest.value == null) return null
		return readingSource.value?.let { SourceSettings(appContext, it).isNovelReadingReversed }
	}

	suspend fun loadChapterHtml(index: Int): String? {
		val target = manga.value ?: return null
		val chapter = chapters.value.getOrNull(index) ?: return null
		if (chapter.source.name == "LOCAL") return LocalMangaParser(chapter.url.toUri()).getChapterHtml(chapter)
		val repository = repositoryFactory.create(target.source)
		val novelRepository = repository as? org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaRepository
			?: return null
		return novelRepository.getChapterHtml(chapter).also { imageHeaders = novelRepository.imageHeaders }
	}

}
