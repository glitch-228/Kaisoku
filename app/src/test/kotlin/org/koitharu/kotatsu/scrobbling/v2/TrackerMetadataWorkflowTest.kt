package org.koitharu.kotatsu.scrobbling.v2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.getCopyableErrorDetails
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.sync.extended.MetadataOverrideState
import java.io.IOException

class TrackerMetadataWorkflowTest {
    private val manga = Manga(10, "Source title", emptySet(), "/edition-a", "https://source.invalid/edition-a", 0.8f,
        ContentRating.SAFE, "https://source.invalid/cover", emptySet(), null, emptySet(), description = "Source synopsis",
        source = MangaParserSource.MANGALIB)
    private val previous = MangaOverride("file:///old-cover.jpg", "Own title", ContentRating.SUGGESTIVE, "Own synopsis")
    private val all = TrackerMetadataSelection(true, true, true)

    @Test fun opaqueProviderOwnersAndMediaIdsNeverEnterNumericContracts() = runBlocking {
        for (provider in listOf("COMICK", "ANIME_PLANET", "MANGABAKA")) {
            val f = Fixture(provider)
            val preview = f.prepare()
            assertEquals(provider, preview.provider)
            assertEquals("opaque-account:$provider", preview.connection.owner.accountId)
            assertEquals("opaque-media:edition", preview.local.link.reference.mediaId)
            f.workflow.apply(preview, TrackerMetadataSelection(true, false, false))
            assertEquals("Tracker title", f.access.local.previous!!.title)
            assertEquals(1, f.access.metadataReads)
            assertEquals(1, f.access.commits)
            assertEquals(0, f.covers.stages)
        }
    }

    @Test fun eachFieldIsIndependentAndUnselectedOverridesAndRatingArePreserved() = runBlocking {
        for (mask in 1..7) {
            val f = Fixture()
            val selected = TrackerMetadataSelection(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
            val preview = f.prepare()
            assertEquals(0, f.access.commits)
            f.workflow.apply(preview, selected)
            val saved = f.access.local.previous!!
            assertEquals(if (selected.title) "Tracker title" else previous.title, saved.title)
            assertEquals(if (selected.description) "Tracker synopsis" else previous.description, saved.description)
            assertEquals(if (selected.cover) "file:///new-cover.jpg" else previous.coverUrl, saved.coverUrl)
            assertEquals(previous.contentRating, saved.contentRating)
            assertEquals(if (selected.cover) 1 else 0, f.covers.stages)
            assertEquals(0, f.covers.deleted)
            assertEquals(manga, f.access.local.manga)
            assertEquals(1, f.access.commits)
        }
    }

    @Test fun anEmptySelectionAndUnavailableFieldsCannotWriteOrDownload() = runBlocking {
        val f = Fixture()
        f.access.remote = f.access.remote.copy(title = " ", description = null, coverUrl = "http://insecure.invalid/cover")
        val preview = f.prepare()
        assertNull(preview.title); assertNull(preview.description); assertNull(preview.cover)
        assertNotNull(runCatching { f.workflow.apply(preview, TrackerMetadataSelection(false, false, false)) }.exceptionOrNull())
        assertNotNull(runCatching { f.workflow.apply(preview, all) }.exceptionOrNull())
        assertEquals(0, f.access.commits); assertEquals(0, f.covers.stages)
        f.access.remote = f.access.remote.copy(coverUrl = "https://user:password@private.invalid/cover")
        assertNull(f.prepare().cover)
    }

    @Test fun onlyAConfirmedExactAccountAndWorkCanPrepareMetadata() = runBlocking {
        val f = Fixture()
        f.access.linked = false
        assertNotNull(runCatching { f.prepare() }.exceptionOrNull())
        assertEquals(0, f.access.metadataReads)
        f.access.linked = true
        f.access.remote = f.access.remote.copy(mediaId = "another-adaptation")
        assertNotNull(runCatching { f.prepare() }.exceptionOrNull())
        assertEquals(0, f.access.commits); assertEquals(0, f.covers.stages)
        f.access.protected = true
        assertNotNull(runCatching { f.prepare() }.exceptionOrNull())
        assertEquals(1, f.access.metadataReads)
    }

    @Test fun changesWhileMetadataIsInFlightInvalidateThePreview() = runBlocking {
        val edits: List<(Fixture) -> Unit> = listOf(
            { it.access.currentConnection = it.access.currentConnection.copy(revision = 2) },
            { it.access.local = it.access.local.copy(link = it.access.local.link.copy(revision = "new-link")) },
            { it.access.local = it.access.local.copy(manga = manga.copy(url = "/another-edition")) },
            { it.access.local = it.access.local.copy(previous = previous.copy(description = "Newer override")) },
            { it.access.local = it.access.local.copy(causality = listOf(state(2))) },
        )
        for (edit in edits) {
            val f = Fixture()
            f.access.duringMetadata = { edit(f) }
            assertNotNull(runCatching { f.prepare() }.exceptionOrNull())
            assertEquals(0, f.access.commits); assertEquals(0, f.covers.stages)
        }
    }

    @Test fun staleAccountLinkEditionAndSameValueRevisionFailBeforeCoverIO() = runBlocking {
        val edits: List<(Fixture) -> Unit> = listOf(
            { it.access.currentConnection = it.access.currentConnection.copy(owner = TrackerOwner("COMICK", "another-account")) },
            { it.access.local = it.access.local.copy(link = it.access.local.link.copy(revision = "relinked")) },
            { it.access.local = it.access.local.copy(manga = manga.copy(source = MangaParserSource.REMANGA)) },
            { it.access.local = it.access.local.copy(manga = manga.copy(publicUrl = "https://source.invalid/migrated")) },
            { it.access.local = it.access.local.copy(previous = previous.copy(title = "Edited")) },
            { it.access.local = it.access.local.copy(causality = listOf(state(99))) },
        )
        for (edit in edits) {
            val f = Fixture()
            val preview = f.prepare()
            edit(f)
            assertNotNull(runCatching { f.workflow.apply(preview, all) }.exceptionOrNull())
            assertEquals(0, f.covers.stages); assertEquals(0, f.access.commits)
        }
    }

    @Test fun changesAfterCoverStagingAndAtCommitDeleteOnlyTheNewUnreferencedAsset() = runBlocking {
        val f = Fixture()
        val preview = f.prepare()
        f.covers.afterStage = { f.access.local = f.access.local.copy(previous = previous.copy(title = "New local edit")) }
        assertNotNull(runCatching { f.workflow.apply(preview, all) }.exceptionOrNull())
        assertEquals(1, f.covers.deleted); assertEquals(0, f.access.commits)
        assertEquals("file:///old-cover.jpg", f.access.local.previous!!.coverUrl)
        val race = Fixture()
        val racePreview = race.prepare()
        race.access.beforeCommit = { race.access.local = race.access.local.copy(link = race.access.local.link.copy(revision = "changed")) }
        assertNotNull(runCatching { race.workflow.apply(racePreview, all) }.exceptionOrNull())
        assertEquals(1, race.covers.deleted); assertEquals(0, race.access.commits)
    }

    @Test fun expiryDiscardRecreationAndDuplicateApplyCannotReuseAReview() = runBlocking {
        val f = Fixture()
        val expired = f.prepare()
        f.moment = expired.expiresAt
        assertNotNull(runCatching { f.workflow.apply(expired, all) }.exceptionOrNull())
        val discarded = f.prepare()
        f.workflow.discard(discarded)
        assertNotNull(runCatching { f.workflow.apply(discarded, all) }.exceptionOrNull())
        val oldProcess = f.prepare()
        val recreated = TrackerMetadataWorkflow(f.access, f.covers) { f.moment }
        assertNotNull(runCatching { recreated.apply(oldProcess, all) }.exceptionOrNull())
        val selected = f.prepare()
        f.workflow.apply(selected, TrackerMetadataSelection(true, false, false))
        assertNotNull(runCatching { f.workflow.apply(selected, TrackerMetadataSelection(true, false, false)) }.exceptionOrNull())
        assertEquals(1, f.access.commits); assertEquals(0, f.covers.stages)
    }

    @Test fun coverFailureIsAtomicAndAStillCurrentReviewCanRetry() = runBlocking {
        val f = Fixture()
        val preview = f.prepare()
        f.covers.failure = IOException("fixture cover failure")
        assertTrue(runCatching { f.workflow.apply(preview, all) }.exceptionOrNull() is IOException)
        assertEquals(previous, f.access.local.previous); assertEquals(0, f.access.commits)
        f.covers.failure = null
        f.workflow.apply(preview, all)
        assertEquals(1, f.access.commits)
        assertEquals("file:///new-cover.jpg", f.access.local.previous!!.coverUrl)
    }

    @Test fun cancellationBeforeCommitCleansStagingButAfterCommitKeepsTheReferencedFile() = runBlocking {
        val canceled = Fixture()
        val preview = canceled.prepare()
        canceled.access.beforeCommit = { throw CancellationException("fixture cancel") }
        assertTrue(runCatching { canceled.workflow.apply(preview, all) }.exceptionOrNull() is CancellationException)
        assertEquals(0, canceled.access.commits); assertEquals(1, canceled.covers.deleted)
        val committed = Fixture()
        val committedPreview = committed.prepare()
        committed.access.afterCommit = { throw CancellationException("canceled after Room commit") }
        assertTrue(runCatching { committed.workflow.apply(committedPreview, all) }.exceptionOrNull() is CancellationException)
        assertEquals(1, committed.access.commits); assertEquals(0, committed.covers.deleted)
        assertEquals("file:///new-cover.jpg", committed.access.local.previous!!.coverUrl)
    }

    @Test fun canceledMetadataReadAndProtectedArchivesNeverStageOrWrite() = runBlocking {
        val f = Fixture()
        f.access.duringMetadata = { throw CancellationException("fixture metadata cancel") }
        assertTrue(runCatching { f.prepare() }.exceptionOrNull() is CancellationException)
        assertEquals(0, f.access.commits); assertEquals(0, f.covers.stages)
        f.access.duringMetadata = null
        val preview = f.prepare()
        f.access.protected = true
        assertNotNull(runCatching { f.workflow.apply(preview, all) }.exceptionOrNull())
        assertEquals(0, f.covers.stages); assertEquals(0, f.access.commits)
    }

    @Test fun coversHideSignedQueriesCredentialsAndFragmentsInUiAndNestedDiagnostics() {
        assertEquals("https://cover.invalid/a.jpg", trackerMetadataCoverDisplay("https://user:password@cover.invalid/a.jpg?signature=secret#private"))
        val failure = TrackerMetadataCoverFailure(IOException("https://user:password@cover.invalid/a.jpg?signature=secret#private Authorization: Bearer bearer-secret"))
        val details = IOException("wrapper https://other.invalid/path?opaque=private", failure).getCopyableErrorDetails()
        listOf("password", "signature=secret", "bearer-secret", "opaque=private", "#private").forEach {
            assertFalse(details.contains(it))
        }
        assertTrue(details.contains("cover.invalid/a.jpg"))
        assertTrue(details.contains("other.invalid/path"))
    }

    private fun state(wall: Long) = MetadataOverrideState(manga.id, manga.source.name, manga.url, "title", previous.title,
        revisionWall = wall, revisionLogical = 0, revisionActor = "fixture")

    private inner class Fixture(provider: String = "COMICK") {
        var moment = 1_000L
        val access = Access(provider)
        val covers = Covers()
        val workflow = TrackerMetadataWorkflow(access, covers) { moment }
        suspend fun prepare() = workflow.prepare(manga.id, access.currentConnection.owner.provider)
    }

    private inner class Access(provider: String) : TrackerMetadataAccess {
        var currentConnection = TrackerConnection(TrackerOwner(provider, "opaque-account:$provider"), 1)
        var local = TrackerMetadataLocal(manga, IncomingProgressLink(
            TrackerReference(currentConnection.owner, "opaque-media:edition", "opaque-list-entry"), "link-v1"), previous,
            listOf(state(1)))
        var remote = TrackerTitle("opaque-media:edition", "Tracker title", coverUrl = "https://cover.invalid/cover.jpg?signature=signed",
            description = "Tracker synopsis", url = "https://tracker.invalid/opaque-media")
        var linked = true
        var protected = false
        var duringMetadata: (suspend () -> Unit)? = null
        var beforeCommit: (suspend () -> Unit)? = null
        var afterCommit: (suspend () -> Unit)? = null
        var metadataReads = 0
        var commits = 0
        override suspend fun connection(provider: String): TrackerConnection {
            require(provider == currentConnection.owner.provider)
            return currentConnection
        }
        override fun checkConnection(connection: TrackerConnection) { check(connection == currentConnection) { "account changed" } }
        override suspend fun snapshot(connection: TrackerConnection, mangaId: Long): TrackerMetadataLocal {
            checkConnection(connection); check(linked); check(!protected); require(mangaId == local.manga.id)
            return local
        }
        override suspend fun metadata(connection: TrackerConnection, mediaId: String, check: suspend () -> Unit): TrackerTitle {
            check(); metadataReads++; require(mediaId == local.link.reference.mediaId)
            duringMetadata?.invoke(); check()
            return remote
        }
        override suspend fun commit(connection: TrackerConnection, expected: TrackerMetadataLocal, value: MangaOverride) {
            beforeCommit?.invoke()
            checkConnection(connection); check(snapshot(connection, expected.manga.id) == expected)
            currentCoroutineContext().ensureActive()
            local = local.copy(previous = value)
            commits++
            afterCommit?.invoke()
        }
        override suspend fun coverIsReferenced(mangaId: Long, uri: String): Boolean = local.previous?.coverUrl == uri
    }

    private class Covers : TrackerMetadataCoverStore {
        var stages = 0
        var deleted = 0
        var failure: Exception? = null
        var afterStage: (() -> Unit)? = null
        override suspend fun stage(url: String, check: suspend () -> Unit): TrackerMetadataCover {
            check(); stages++; failure?.let { throw it }
            val cover = TrackerMetadataCover("file:///new-cover.jpg") { deleted++ }
            afterStage?.invoke()
            return cover
        }
    }
}
