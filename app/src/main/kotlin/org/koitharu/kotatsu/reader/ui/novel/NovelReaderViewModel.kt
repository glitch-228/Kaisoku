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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.HistoryUpdateUseCase
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import android.content.Context
import javax.inject.Inject

@HiltViewModel
class NovelReaderViewModel @Inject constructor(
	@ApplicationContext appContext: Context,
	savedStateHandle: SavedStateHandle,
	private val dataRepository: MangaDataRepository,
	private val repositoryFactory: MangaRepository.Factory,
	private val historyRepository: HistoryRepository,
	private val historyUpdateUseCase: HistoryUpdateUseCase,
) : BaseViewModel() {

	private val intent = MangaIntent(savedStateHandle)

	/** Explicitly requested position (e.g. a chapter-row tap), wins over history. */
	private val requestedState: ReaderState? = savedStateHandle[EXTRA_STATE]

	val isIncognitoMode: Boolean = savedStateHandle[EXTRA_INCOGNITO] ?: false

	val manga = MutableStateFlow<Manga?>(null)
	val chapters = MutableStateFlow<List<MangaChapter>>(emptyList())
	val currentChapterIndex = MutableStateFlow(-1)
	val isUiLoading = MutableStateFlow(false)
	val readerSettings = MutableStateFlow(NovelReaderSettings.load(appContext))
	val initialRatio = MutableStateFlow<Float?>(null)

	private var lastSavedState: ReaderState? = null
	private var lastSavedRatio = 0f

	init {
		launchLoadingJob(Dispatchers.Default) {
			val target = dataRepository.resolveIntent(intent, withChapters = true)
				?: error("Cannot resolve novel ${intent.mangaId}")
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
			currentChapterIndex.value = resolveInitialChapterIndex(manga.value ?: target, requested, history)
			initialRatio.value = resolveInitialRatio(requested, history)
		}
	}

	private fun resolveInitialChapterIndex(
		target: Manga,
		requestedState: ReaderState?,
		history: MangaHistory?,
	): Int {
		val chaptersList = target.chapters.orEmpty()
		if (chaptersList.isEmpty()) return -1
		val state = requestedState ?: history?.let { ReaderState(it) }
		val index = state?.let { s -> chaptersList.indexOfFirst { it.id == s.chapterId } }
		return if (index != null && index >= 0) index else 0
	}

	private fun resolveInitialRatio(requestedState: ReaderState?, history: MangaHistory?): Float? {
		val scroll = requestedState?.scroll ?: history?.scroll ?: 0
		if (scroll <= 0) return null
		return (scroll / SCROLL_RATIO_SCALE).coerceIn(0f, 1f)
	}

	companion object {

		/** Extras shared with [ReaderIntent] (same key strings). */
		const val EXTRA_STATE = ReaderIntent.EXTRA_STATE
		const val EXTRA_INCOGNITO = ReaderIntent.EXTRA_INCOGNITO

		private const val SCROLL_RATIO_SCALE = 10_000f
	}

	/** Persist the current reading position. Ratio: 0..1 within the chapter. */
	fun saveProgress(chapterIndex: Int, ratio: Float) {
		val target = manga.value ?: return
		val chapter = chapters.value.getOrNull(chapterIndex) ?: return
		if (isIncognitoMode) {
			return
		}
		val total = chapters.value.size
		val percent = if (total > 0) (chapterIndex + ratio) / total else 0f
		val state = ReaderState(
			chapterId = chapter.id,
			page = 0,
			scroll = (ratio * SCROLL_RATIO_SCALE).toInt(),
		)
		lastSavedRatio = ratio
		if (lastSavedState == state) return
		lastSavedState = state
		launchJob(Dispatchers.Default) {
			historyUpdateUseCase(target, state, percent.coerceIn(0f, 1f))
		}
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
		if (chapterIndex == currentChapterIndex.value) {
			return lastSavedRatio
		}
		return null
	}

	fun switchChapter(index: Int) {
		if (index in chapters.value.indices) {
			currentChapterIndex.value = index
		}
	}

	suspend fun loadChapterHtml(index: Int): String? {
		val target = manga.value ?: return null
		val chapter = chapters.value.getOrNull(index) ?: return null
		val repository = repositoryFactory.create(target.source)
		val novelRepository = repository as? org.koitharu.kotatsu.core.parser.lnreader.LnReaderMangaRepository
			?: return null
		return novelRepository.getChapterHtml(chapter)
	}

	suspend fun getImageHeaders(url: String): Map<String, String> = runCatching {
		if (!url.startsWith("http")) return@runCatching emptyMap()
		val target = manga.value ?: return@runCatching emptyMap()
		val repository = repositoryFactory.create(target.source)
		val client = repository.getImageClient() ?: return@runCatching emptyMap()
		val request = Request.Builder().url(url).build()
		withContext(Dispatchers.IO) {
			client.newCall(request).execute().use { response ->
				HeadersToMap(response.request.headers)
			}
		}
	}.getOrDefault(emptyMap())

	private fun HeadersToMap(headers: Headers): Map<String, String> = buildMap {
		for (name in headers.names()) {
			put(name, headers[name] ?: continue)
		}
	}
}
