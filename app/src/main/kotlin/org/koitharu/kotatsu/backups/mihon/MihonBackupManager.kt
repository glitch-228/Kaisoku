package org.koitharu.kotatsu.backups.mihon

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/** All Mihon imports require a preview; no immediate restore or implicit active tracker binding. */
@Singleton
class MihonBackupManager @Inject constructor(private val importer: MihonReviewedImport) {
    data class RestoreReport(
        val restoredManga: Int,
        val restoredChapters: Int,
        val missingSources: List<String>,
        val restoredTracking: Int,
        val skippedItems: Int,
        val exactTitles: Int = 0,
    )

    suspend fun prepare(uri: Uri, sourceBindings: Map<Long, String> = emptyMap()): MihonImportPreview =
        importer.prepare(uri, sourceBindings)

    suspend fun apply(preview: MihonImportPreview, choices: MihonImportChoices): RestoreReport = importer.apply(preview, choices)
}
