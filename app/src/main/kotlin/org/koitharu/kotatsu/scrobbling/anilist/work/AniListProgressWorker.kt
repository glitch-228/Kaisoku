package org.koitharu.kotatsu.scrobbling.anilist.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository

class AniListProgressWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val repository: AniListRepository,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val userId = inputData.getLong(KEY_USER_ID, 0L)
		val targetId = inputData.getLong(KEY_TARGET_ID, 0L)
		val mangaId = inputData.getLong(KEY_MANGA_ID, 0L)
		val rateId = inputData.getInt(KEY_RATE_ID, 0)
		if (userId <= 0 || targetId <= 0 || mangaId == 0L || rateId <= 0) return Result.failure()
		return try {
			if (repository.retryPendingProgress(userId, targetId, mangaId, rateId)) {
				Result.success()
			} else {
				Result.retry()
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: Throwable) {
			Result.retry()
		}
	}

	companion object {
		const val KEY_USER_ID = "anilist_user_id"
		const val KEY_TARGET_ID = "anilist_media_id"
		const val KEY_MANGA_ID = "anilist_local_manga_id"
		const val KEY_RATE_ID = "anilist_list_entry_id"
	}
}
