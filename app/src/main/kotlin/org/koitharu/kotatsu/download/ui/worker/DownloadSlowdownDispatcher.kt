package org.koitharu.kotatsu.download.ui.worker

import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {
	private val nextRequestAt = MutableObjectLongMap<MangaSource>()

	suspend fun delay(source: MangaSource) {
		val repo = mangaRepositoryFactory.create(source) as? ParserMangaRepository ?: return
		if (!repo.isSlowdownEnabled()) {
			return
		}
		val now = SystemClock.elapsedRealtime()
		val slot = reserveSlowdownSlot(nextRequestAt, source, now, REQUEST_INTERVAL)
		delay((slot - now).coerceAtLeast(0L))
	}

	private companion object {

		const val REQUEST_INTERVAL = 1_600L
	}
}

internal fun reserveSlowdownSlot(
	nextRequestAt: MutableObjectLongMap<MangaSource>,
	source: MangaSource,
	now: Long,
	interval: Long,
): Long = synchronized(nextRequestAt) {
	val slot = maxOf(nextRequestAt.getOrDefault(source, now), now)
	nextRequestAt[source] = slot + interval
	slot
}
