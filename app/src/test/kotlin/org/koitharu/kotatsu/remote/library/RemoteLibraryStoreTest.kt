package org.koitharu.kotatsu.remote.library

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteLibraryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun connectionAndAcquisitionSurviveRecreationAndRenameWithoutCredentials() {
        val file = File(temporary.root, "connections.json")
        val original = connection()
        val record = record(original)
        val store = RemoteLibraryStore(file)
        store.save(original)
        store.record(record)
        store.save(original.copy(name = "Renamed"))
        val reopened = RemoteLibraryStore(file)
        assertEquals(original.copy(name = "Renamed"), reopened.connection(original.id))
        assertEquals(listOf(record), reopened.acquired(original.id, record.publicationId))
        val serialized = reopened.exportTransfer()
        assertFalse(serialized.contains("password"))
        assertFalse(serialized.contains("Authorization"))
        assertFalse(serialized.contains("access_token"))
        assertTrue(serialized.contains(Long.MAX_VALUE.toString()))
        assertTrue(serialized.contains("edition:000123/A"))
        assertEquals(record.metadata, reopened.acquiredForLocal(record.localMangaId, record.localUrl)?.metadata)
    }

    @Test fun restoredConnectionsRemainDisabledAndExistingEnablementRemainsLocal() {
        val original = connection()
        val source = RemoteLibraryStore(File(temporary.root, "source.json")).apply { save(original); record(record(original)) }
        val destination = RemoteLibraryStore(File(temporary.root, "destination.json"))
        val preview = destination.previewTransfer(source.exportTransfer())
        assertEquals(listOf(original.copy(enabled = false)), preview.connectionsToAdd)
        assertEquals(1, preview.acquisitionsToAdd.size)
        assertTrue(destination.connections().isEmpty())
        destination.mergeTransfer(source.exportTransfer())
        assertFalse(destination.connection(original.id)!!.enabled)
        destination.save(original.copy(name = "Local name", enabled = true))
        destination.mergeTransfer(source.exportTransfer())
        assertTrue(destination.connection(original.id)!!.enabled)
        assertEquals("Local name", destination.connection(original.id)!!.name)
        assertEquals(1, destination.acquisitions().size)
    }

    @Test fun scopeAndAccountConflictsCannotRebindSavedPublicationIdentities() {
        val original = connection()
        val existing = RemoteLibraryStore(File(temporary.root, "existing.json")).apply { save(original); record(record(original)) }
        val foreign = RemoteLibraryStore(File(temporary.root, "foreign.json")).apply {
            save(original.copy(account = "another-reader")); record(record(original).copy(localMangaId = 42))
        }
        val before = existing.exportTransfer()
        val result = existing.previewTransfer(foreign.exportTransfer())
        assertEquals(listOf(original.id), result.conflicts)
        assertTrue(result.connectionsToAdd.isEmpty())
        assertTrue(result.acquisitionsToAdd.isEmpty())
        assertThrows(RemoteLibraryException::class.java) { existing.mergeTransfer(foreign.exportTransfer()) }
        assertEquals(before, existing.exportTransfer())
        assertThrows(LibraryScopeException::class.java) { existing.save(original.copy(scopeUrl = "https://else.invalid/")) }
        assertEquals(before, existing.exportTransfer())
    }

    @Test fun malformedUnsupportedOrMissingSectionsDoNotPartiallyUpdateState() {
        val original = connection()
        val store = RemoteLibraryStore(File(temporary.root, "connections.json")).apply { save(original) }
        val before = store.exportTransfer()
        for (value in listOf("{}", """{"version":2,"connections":[],"acquisitions":[]}""",
            """{"version":1,"connections":[]}""", before.replace("https://library.invalid/", "http://library.invalid/"))) {
            assertNotNull(runCatching { store.mergeTransfer(value) }.exceptionOrNull())
            assertEquals(before, store.exportTransfer())
        }
    }

    @Test fun samePublicationWithDifferentDigestsRetainsDistinctAcquiredOriginals() {
        val original = connection()
        val store = RemoteLibraryStore(File(temporary.root, "connections.json")).apply { save(original) }
        val first = record(original)
        val second = first.copy(sha256 = "b".repeat(64), localUrl = "file:///private/another.cbz", localMangaId = 43)
        store.record(first); store.record(second); store.record(first)
        assertEquals(listOf(first, second), RemoteLibraryStore(File(temporary.root, "connections.json")).acquired(original.id, first.publicationId))
    }

    @Test fun persistenceRecreationPublishesExactMetadataAndAtomicConflictsDoNotMutateRegistry() = kotlinx.coroutines.runBlocking {
        val original = connection()
        val book = temporary.newFile("original.cbz").apply { writeText("unchanged original") }
        val record = record(original).copy(localUrl = book.toPath().toUri().toString(), sha256 = digest(book))
        val file = File(temporary.root, "connections.json")
        RemoteLibraryStore(file).apply {
            save(original)
            recordVerified(record, LibraryAcquisitionVerifier.verify(record, original))
        }
        val persistence = RemoteLibraryPersistence { RemoteLibraryStore(file) }
        persistence.initialize()
        assertEquals("Home", RemoteLibrarySource(original.id).resolved().displayName)
        assertEquals(record.metadata, RemoteLibrarySourceRegistry.acquiredMetadata(record.localMangaId, record.localUrl))
        assertNull(RemoteLibrarySourceRegistry.acquiredMetadata(record.localMangaId, "file:///different/book.cbz"))
        assertEquals(0L, persistence.changes.value)
        val foreign = RemoteLibraryStore(File(temporary.root, "foreign.json")).apply {
            save(original.copy(account = "different")); record(record.copy(localMangaId = 99))
        }
        val before = persistence.exportTransfer()
        assertTrue(runCatching { persistence.mergeTransfer(foreign.exportTransfer()) }.exceptionOrNull() is RemoteLibraryException)
        assertEquals(before, persistence.exportTransfer())
        assertEquals(0L, persistence.changes.value)
        assertEquals(record.metadata, RemoteLibrarySourceRegistry.acquiredMetadata(record.localMangaId, record.localUrl))
        assertNull(RemoteLibrarySourceRegistry.acquiredMetadata(99, record.localUrl))
    }

    @Test fun restoredReceiptsNeedByteVerificationAndReplacementsLoseTrustedMetadata() = kotlinx.coroutines.runBlocking {
        val original = connection()
        val book = temporary.newFile("book.cbz").apply { writeText("original bytes") }
        val record = record(original).copy(localUrl = book.toPath().toUri().toString(), sha256 = digest(book),
            metadata = record(original).metadata!!.copy(content = RemoteLibraryContent.SAFE))
        val source = RemoteLibraryStore(File(temporary.root, "source.json")).apply {
            save(original); recordVerified(record, LibraryAcquisitionVerifier.verify(record, original))
        }
        val target = RemoteLibraryStore(File(temporary.root, "restored.json"))
        target.mergeTransfer(source.exportTransfer())
        assertEquals("", target.metadataForLocal(record.localMangaId, record.localUrl)!!.title)
        assertEquals(RemoteLibraryContent.UNKNOWN, target.metadataForLocal(record.localMangaId, record.localUrl)!!.content)
        val connection = target.connection(original.id)!!
        target.markVerified(record, LibraryAcquisitionVerifier.verify(record, connection))
        assertEquals(record.metadata, target.metadataForLocal(record.localMangaId, record.localUrl))
        assertEquals(record.metadata, RemoteLibraryStore(File(temporary.root, "restored.json"))
            .metadataForLocal(record.localMangaId, record.localUrl))
        val replaced = temporary.newFile("replacement.cbz").apply { writeText("different data") }
        java.nio.file.Files.move(replaced.toPath(), book.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals("", target.metadataForLocal(record.localMangaId, record.localUrl)!!.title)
        assertEquals(RemoteLibraryContent.UNKNOWN, target.metadataForLocal(record.localMangaId, record.localUrl)!!.content)
        assertTrue(runCatching { LibraryAcquisitionVerifier.verify(record, connection) }.exceptionOrNull() is RemoteLibraryException)
        assertFalse(source.exportTransfer().contains("fileKey"))
        assertFalse(source.exportTransfer().contains("verified"))
    }

    @Test fun wrongBytesScopeChangesAndCollidingReceiptsCannotPromoteMetadata() = kotlinx.coroutines.runBlocking {
        val original = connection()
        val book = temporary.newFile("book.cbz").apply { writeText("original") }
        val record = record(original).copy(localUrl = book.toPath().toUri().toString(), sha256 = digest(book))
        val store = RemoteLibraryStore(File(temporary.root, "state.json")).apply { save(original); record(record) }
        val verification = LibraryAcquisitionVerifier.verify(record, original)
        val changedScope = verification.copy(scope = LibraryAcquisitionVerifier.scope(original.copy(account = "another")))
        assertTrue(runCatching { store.markVerified(record, changedScope) }.isFailure)
        store.markVerified(record, verification)
        val collision = record.copy(publicationId = "other-publication", metadata = record.metadata!!.copy(title = "Wrong title"))
        store.record(collision)
        val metadata = store.metadataForLocal(record.localMangaId, record.localUrl)!!
        assertEquals("", metadata.title)
        assertTrue(metadata.authors.isEmpty())
        assertNull(metadata.description)
        assertEquals(RemoteLibraryContent.UNKNOWN, metadata.content)
        val file = temporary.newFile("wrong.cbz").apply { writeText("not original") }
        assertTrue(runCatching { LibraryAcquisitionVerifier.verify(record.copy(localUrl = file.toPath().toUri().toString()), original) }.isFailure)
    }

    private fun digest(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun connection() = RemoteLibraryConnection("00000000-0000-0000-0000-000000000034", "Home",
        "https://library.invalid/opds/catalog", "https://library.invalid/", RemoteLibraryKind.OPDS, "reader", RemoteLibraryContent.MIXED)
    private fun record(connection: RemoteLibraryConnection) = LibraryAcquisitionRecord(connection.id, "edition:000123/A", Long.MAX_VALUE,
        "file:///private/acquired.cbz", "a".repeat(64), LibraryFormat.CBZ,
        LibraryAcquiredMetadata("Book", setOf("Author"), setOf("Genre"), "Synopsis", RemoteLibraryContent.UNKNOWN))
}
