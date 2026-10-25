package org.koitharu.kotatsu.reader.ui.novel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ChapterTextRepository
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal data class NovelSpeechRequest(
    val manga: Manga,
    val chapters: List<MangaChapter>,
    val firstChapter: Int,
    val firstText: String,
    val ratio: Float,
    val locale: Locale?,
    val translated: Boolean,
    val cachedTranslations: Map<Long, String>,
)

internal enum class NovelSpeechStatus { STOPPED, PREPARING, LOADING, PLAYING, PAUSED }
internal data class NovelSpeechState(
    val status: NovelSpeechStatus = NovelSpeechStatus.STOPPED,
    val mangaId: Long? = null,
    val title: String = "",
    val chapterTitle: String = "",
    val error: Int? = null,
) {
    val isActive get() = status != NovelSpeechStatus.STOPPED
}

/** Installed Android engine only. Service owns playback; activities only request/control it. */
@Singleton
internal class NovelSpeechController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repositoryFactory: MangaRepository.Factory,
) {
    private var engine: TextToSpeech? = null
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cursor = NovelSpeechSession()
    private val serviceOwnership = NovelSpeechServiceOwnership()
    private val archiveSession = NovelSpeechArchiveSession(
        org.koitharu.kotatsu.core.archive.ArchiveAccess.get(context).sessions,
    )
    private var request: NovelSpeechRequest? = null
    private var protectedFile: java.io.File? = null
    private var chunks = emptyList<String>()
    private var spokenOffset = 0
    private var loadJob: Job? = null
    private var pending: (() -> Unit)? = null
    private var pendingActivity: Activity? = null
    private var voiceDialog: AlertDialog? = null
    private var voiceActivity: Activity? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var focusGeneration = 0L
    private var serviceStarted = false
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kaisoku:novel_speech")
    private val mutableState = MutableStateFlow(NovelSpeechState())
    val state = mutableState.asStateFlow()
    val isActive get() = state.value.isActive
    val mangaSnapshot: Manga? get() = request?.manga

    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    init {
        org.koitharu.kotatsu.core.archive.ArchiveAccess.get(context).sessions.addLockListener { paths ->
            if (protectedFile?.canonicalPath in paths) {
                if (Looper.myLooper() == Looper.getMainLooper()) stop() else handler.post { stop() }
            }
        }
    }

    fun start(activity: Activity, value: NovelSpeechRequest) {
        stop()
        if (value.firstText.isBlank() || value.firstChapter !in value.chapters.indices) return
        try {
            protectedFile = org.koitharu.kotatsu.core.archive.ArchivePrivacy.file(value.manga)?.takeIf {
                org.koitharu.kotatsu.core.archive.ArchiveAccess.get(context).isSensitive(it)
            }
            archiveSession.capture(protectedFile)
        } catch (_: Exception) {
            stop()
            return
        }
        serviceOwnership.newRequest()
        request = value
        cursor.seekChapter(value.firstChapter)
        publish(NovelSpeechStatus.PREPARING)
        val token = cursor.generation
        val action = action@ {
            if (token == cursor.generation && isActive && !activity.isFinishing && !activity.isDestroyed) {
                if (!requireArchiveSession()) return@action
                try { chooseVoice(activity, value.locale) } catch (_: Exception) { fail(R.string.novel_speech_unavailable) }
            }
        }
        if (ready) action() else {
            pending = action
            pendingActivity = activity
            if (engine == null) {
                engine = TextToSpeech(context) { status ->
                    handler.post {
                        ready = status == TextToSpeech.SUCCESS
                        if (ready) {
                            engine?.setAudioAttributes(audioAttributes)
                            val selected = pending
                            pending = null
                            pendingActivity = null
                            selected?.invoke()
                        } else {
                            pending = null
                            pendingActivity = null
                            engine?.shutdown()
                            engine = null
                            if (isActive) fail(R.string.novel_speech_unavailable)
                        }
                    }
                }.apply {
                    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            handler.post {
                                val text = chunks.getOrNull(cursor.chunk) ?: return@post
                                if (state.value.status == NovelSpeechStatus.PLAYING) {
                                    cursor.rememberOffset(utteranceId, spokenOffset + start, text.length)
                                }
                            }
                        }
                        override fun onDone(utteranceId: String?) {
                            handler.post {
                                if (state.value.status == NovelSpeechStatus.PLAYING && cursor.accepts(utteranceId)) {
                                    cursor.advanceChunk()
                                    speakNext()
                                }
                            }
                        }
                        @Deprecated("Required for installed speech engines")
                        override fun onError(utteranceId: String?) {
                            handler.post {
                                if (isActive && cursor.accepts(utteranceId)) fail(R.string.novel_speech_unavailable)
                            }
                        }
                    })
                }
            }
        }
    }

    private fun chooseVoice(activity: Activity, locale: Locale?) {
        val tts = engine ?: return
        val language = locale ?: tts.defaultVoice?.locale ?: Locale.getDefault()
        if (tts.setLanguage(language) < TextToSpeech.LANG_AVAILABLE) {
            fail(R.string.novel_speech_language_unavailable)
            return
        }
        val voices = tts.voices.orEmpty().filter { it.locale.language == language.language }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
        if (voices.isEmpty()) { startService(); return }
        val token = cursor.generation
        voiceActivity = activity
        voiceDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.novel_speech_voice)
            .setItems(voices.map { voice ->
                "${voice.locale.displayName} · ${voice.name}" +
                    if (voice.isNetworkConnectionRequired) " (${activity.getString(R.string.novel_speech_network)})" else ""
            }.toTypedArray()) { _, selected ->
                voiceDialog = null
                voiceActivity = null
                if (token == cursor.generation && isActive) {
                    if (!requireArchiveSession()) return@setItems
                    try {
                        if (tts.setVoice(voices[selected]) == TextToSpeech.SUCCESS) startService()
                        else fail(R.string.novel_speech_unavailable)
                    } catch (_: Exception) { fail(R.string.novel_speech_unavailable) }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> stop() }
            .setOnCancelListener { stop() }
            .show()
    }

    private fun startService() {
        if (!requireArchiveSession()) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, NovelSpeechService::class.java)
                .putExtra(NovelSpeechService.EXTRA_REQUEST, serviceOwnership.attach()))
        } catch (_: Exception) { fail(R.string.novel_speech_unavailable) }
    }

    /** Called only after startForeground: Android 15+ requires it before background audio focus. */
    fun acceptsServiceStart(token: Long?): Boolean = isActive && serviceOwnership.owns(token)

    fun onServiceStarted(token: Long): Long? {
        if (!acceptsServiceStart(token)) return null
        if (!requireArchiveSession()) return null
        serviceStarted = true
        request?.let { loadChapter(it.firstChapter, it.firstText, it.ratio) }
        return token
    }

    fun onServiceDestroyed(token: Long?) {
        if (!serviceOwnership.owns(token)) return
        serviceStarted = false
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    fun detachActivity(activity: Activity) {
        // Playback is independent of rotation/background. A voice picker has no foreground service yet.
        if (voiceActivity === activity || pendingActivity === activity) stop()
    }

    fun toggle() = if (state.value.status == NovelSpeechStatus.PAUSED) resume() else pause()

    fun pause() {
        if (!isActive || state.value.status == NovelSpeechStatus.PREPARING ||
            state.value.status == NovelSpeechStatus.PAUSED) return
        cursor.invalidate()
        loadJob?.cancel()
        loadJob = null
        runCatching { engine?.stop() }
        releaseFocus()
        publish(NovelSpeechStatus.PAUSED)
    }

    fun resume() {
        if (state.value.status != NovelSpeechStatus.PAUSED || !serviceStarted) return
        if (!requireArchiveSession()) return
        if (loadJob?.isActive == true) publish(NovelSpeechStatus.LOADING)
        else if (chunks.isNotEmpty()) speakNext()
        else loadChapter(cursor.chapter)
    }

    fun skipChapter(delta: Int) {
        if (!serviceStarted) return
        val value = request ?: return
        val next = cursor.chapter + delta
        if (next !in value.chapters.indices) return
        cursor.invalidate()
        runCatching { engine?.stop() }
        releaseFocus()
        loadChapter(next)
    }

    private fun loadChapter(index: Int, initialText: String? = null, ratio: Float = 0f) {
        if (!requireArchiveSession()) return
        loadJob?.cancel()
        cursor.seekChapter(index)
        chunks = emptyList()
        val value = request ?: return
        val chapter = value.chapters.getOrNull(index) ?: run { stop(); return }
        val token = cursor.generation
        publish(NovelSpeechStatus.LOADING)
        if (!isActive) return
        loadJob = scope.launch {
            try {
                val text = initialText ?: value.cachedTranslations[chapter.id] ?: if (value.translated) {
                    // Never silently switch language or submit paid translation from a background service.
                    fail(R.string.novel_speech_translation_boundary)
                    return@launch
                } else withContext(Dispatchers.IO) {
                    val html = if (chapter.source.name == "LOCAL") {
                        LocalMangaParser(chapter.url.toUri()).getChapterHtml(chapter)
                    } else {
                        val repository = repositoryFactory.create(value.manga.source) as? ChapterTextRepository
                            ?: error("Novel source cannot provide text")
                        repository.getChapterHtml(chapter)
                    }
                    html?.let(NovelHtml::decodeChapterHtml)?.let(NovelHtml::toPlainText)
                }
                currentCoroutineContext().ensureActive()
                if (token != cursor.generation || !isActive) return@launch
                if (!requireArchiveSession()) return@launch
                chunks = novelSpeechChunks(novelSpeechContent(text.orEmpty()), ratio,
                    TextToSpeech.getMaxSpeechInputLength())
                if (chunks.isEmpty()) { fail(R.string.novel_speech_unavailable); return@launch }
                if (state.value.status != NovelSpeechStatus.PAUSED) speakNext()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == cursor.generation) fail(R.string.novel_speech_unavailable)
            } finally {
                if (token == cursor.generation) loadJob = null
            }
        }
    }

    private fun speakNext() {
        if (!serviceStarted || !isActive) return
        if (!requireArchiveSession()) return
        val text = chunks.getOrNull(cursor.chunk)
        if (text == null) {
            val next = cursor.chapter + 1
            if (next in request?.chapters.orEmpty().indices) loadChapter(next) else stop()
            return
        }
        if (focusRequest == null) {
            val focusToken = ++focusGeneration
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change < 0) handler.post {
                    if (focusGeneration == focusToken && focusRequest != null) pause()
                }
            }
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener(listener).build()
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                publish(NovelSpeechStatus.PAUSED)
                return
            }
            focusRequest = focus
        }
        // Bound each acquisition; stop/pause release it immediately. No background idle wake lock.
        if (wakeLock.isHeld) wakeLock.release()
        wakeLock.acquire(10 * 60 * 1000L)
        spokenOffset = cursor.offset.coerceAtMost(text.length)
        if (spokenOffset in 1 until text.length && text[spokenOffset].isLowSurrogate() &&
            text[spokenOffset - 1].isHighSurrogate()) spokenOffset--
        publish(NovelSpeechStatus.PLAYING)
        if (!isActive || !requireArchiveSession()) return
        val result = runCatching {
            engine?.speak(text.substring(spokenOffset), TextToSpeech.QUEUE_FLUSH, null, cursor.utteranceId)
        }.getOrNull()
        if (result != TextToSpeech.SUCCESS) fail(R.string.novel_speech_unavailable)
    }

    private fun publish(status: NovelSpeechStatus, error: Int? = null) {
        val value = request
        val generation = cursor.generation
        val protectedRequest = protectedFile != null
        val protectedSpeech = protectedRequest &&
            (status == NovelSpeechStatus.PLAYING || status == NovelSpeechStatus.LOADING)
        if (protectedRequest && !requireArchiveSession()) return
        org.koitharu.kotatsu.core.archive.ArchiveSessionLifecycle.onSpeechChanged(
            protectedSpeech,
        )
        // Changing speech activity can discover that the previous background timer already expired.
        if (generation != cursor.generation || request !== value) return
        if (protectedRequest && !requireArchiveSession()) return
        mutableState.value = NovelSpeechState(status, value?.manga?.id,
            if (protectedFile != null) protectedFile!!.nameWithoutExtension else value?.manga?.title.orEmpty(),
            if (protectedFile != null) "" else value?.chapters?.getOrNull(cursor.chapter)?.title.orEmpty(), error)
    }

    private fun releaseFocus() {
        focusGeneration++
        if (wakeLock.isHeld) wakeLock.release()
        focusRequest?.let(audio::abandonAudioFocusRequest)
        focusRequest = null
    }

    private fun fail(message: Int) {
        val failed = state.value
        stop()
        mutableState.value = failed.copy(status = NovelSpeechStatus.STOPPED, error = message)
    }

    private fun requireArchiveSession(): Boolean = try {
        archiveSession.requireActive()
        true
    } catch (_: Exception) {
        stop()
        false
    }

    fun stop() {
        cursor.invalidate()
        pending = null
        pendingActivity = null
        loadJob?.cancel()
        loadJob = null
        chunks = emptyList()
        voiceDialog?.dismiss()
        voiceDialog = null
        voiceActivity = null
        runCatching { engine?.stop() }
        releaseFocus()
        request = null
        protectedFile = null
        archiveSession.clear()
        org.koitharu.kotatsu.core.archive.ArchiveSessionLifecycle.onSpeechChanged(false)
        serviceStarted = false
        mutableState.value = NovelSpeechState()
    }
}
