package org.koitharu.kotatsu.backups.mihon

import android.net.Uri

data class MihonImportSourceOption(val name: String, val title: String)

data class MihonImportSourceChoice(val sourceId: Long, val title: String, val options: List<MihonImportSourceOption>)

data class MihonImportChapterPreview(val id: Long, val title: String, val url: String, val read: Boolean)

data class MihonImportResumePreview(val chapterId: Long, val title: String, val page: Int)

data class MihonImportTitlePreview(
    val mangaId: Long,
    val title: String,
    val source: String,
    val editionUrl: String,
    val chapters: List<MihonImportChapterPreview>,
    val hasExistingHistory: Boolean,
    val hasExistingExactState: Boolean,
    val alreadyImported: Boolean,
    val proposedResume: MihonImportResumePreview?,
) {
    val readCount: Int get() = chapters.count { it.read }
}

/** No decoded backup, password or tracker token is retained in a preview or saved instance state. */
data class MihonImportPreview internal constructor(
    val uri: Uri,
    val digest: String,
    val titles: List<MihonImportTitlePreview>,
    val missingSources: List<String>,
    val ambiguousSources: List<MihonImportSourceChoice>,
    internal val sourceBindings: Map<Long, String>,
    internal val sourceFingerprint: String,
    internal val databaseFingerprint: String,
) {
    fun defaultChoices() = MihonImportChoices(
        selectedTitles = titles.filterNot { it.alreadyImported }.mapTo(LinkedHashSet()) { it.mangaId },
        exactTitles = titles.filter { !it.hasExistingExactState && !it.alreadyImported && it.chapters.isNotEmpty() }
            .mapTo(LinkedHashSet()) { it.mangaId },
    )
}

data class MihonImportChoices(
    val selectedTitles: Set<Long>,
    val exactTitles: Set<Long>,
    val replaceResumeTitles: Set<Long> = emptySet(),
) {
    internal fun validate(preview: MihonImportPreview) {
        val ids = preview.titles.mapTo(HashSet()) { it.mangaId }
        require(ids.containsAll(selectedTitles) && ids.containsAll(exactTitles) && ids.containsAll(replaceResumeTitles)) {
            "The import selection no longer matches its preview"
        }
    }
}

class StaleMihonImportException : java.io.IOException("The backup, source or local reading state changed. Review the import again.")
