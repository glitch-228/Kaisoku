package org.koitharu.kotatsu.sync.extended.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ExtendedDriveCoordinatorTest {
    @Test fun configurationFinalGuardFailureRetainsPreviousConsent() = fixture { value ->
        value.enable()
        val before = value.store.configuration()
        assertTrue(runCatching { value.store.configure(before.copy(enabled = false)) { error("account changed") } }.isFailure)
        assertEquals(before, value.store.configuration())
    }

    @Test fun privatePendingBudgetStopsNewUploadWithoutRemovingOtherAccountJournals() = fixture { value ->
        value.enable()
        value.store.identity()
        val directory = File(value.store.directory, "publications").apply { mkdirs() }
        val retained = File(directory, "other-account.pending")
        java.io.RandomAccessFile(retained, "rw").use { it.setLength(ExtendedDriveProtocol.MAX_ACCOUNT_BYTES) }
        assertEquals(ExtendedDriveStatus.REVIEW_REQUIRED, value.sync().lanes[CHAPTERS])
        assertEquals(0, value.api.creates)
        assertEquals(ExtendedDriveProtocol.MAX_ACCOUNT_BYTES, retained.length())
        assertNull(value.store.publication(OWNER, CHAPTERS))
    }

    @Test fun disabledAndNonautomaticRunsMakeNoRequests() = fixture { value ->
        assertEquals(ExtendedDriveStatus.DISABLED, value.sync().status)
        value.enable()
        assertEquals(ExtendedDriveStatus.DISABLED, value.coordinator.sync(value.session, automatic = true).status)
        assertEquals(0, value.api.calls)
    }

    @Test fun fiveNamespacesStaySeparateAndHaveOneCommittedFile() = fixture { value ->
        value.enable(ExtendedDriveProtocol.namespaces)
        assertEquals(ExtendedDriveStatus.SUCCESS, value.sync().status)
        assertEquals(5, value.api.files.size)
        assertEquals(ExtendedDriveProtocol.namespaces, value.api.files.values.map { it.descriptor.namespace }.toSet())
        assertTrue(value.api.files.values.all { it.descriptor.name.startsWith(ExtendedDriveProtocol.PREFIX) })
        assertEquals(0, value.api.deletes)
        assertTrue(value.store.directory.walkTopDown().filter { it.isFile }.none { it.readBytes().toString(Charsets.ISO_8859_1)
            .contains("oauth-fixture-token") })
    }

    @Test fun repeatedPublicationsCompactOnlyOwnPredecessorAndNeverExceedTwo() = fixture { value ->
        value.enable()
        repeat(25) { generation ->
            value.payloads.version = generation + 1
            assertEquals(ExtendedDriveStatus.SUCCESS, value.sync().status)
            assertEquals(1, value.api.files.size)
        }
        assertEquals(2, value.api.peak)
        assertEquals(24, value.api.deletes)
        assertEquals(25L, value.api.files.values.single().descriptor.generation)
        assertNull(value.store.publication(OWNER, CHAPTERS))
    }

    @Test fun lostCreateReplyRequiresExplicitResumeAndRetainsExactPreallocatedId() = fixture { value ->
        value.enable()
        value.api.loseCreate = true
        assertEquals(ExtendedDriveStatus.RECOVERY_REQUIRED, value.sync().lanes[CHAPTERS])
        val pending = checkNotNull(value.store.publication(OWNER, CHAPTERS))
        value.payloads.version = 2
        value.sync()
        assertEquals(1, value.api.creates)
        value.recreate()
        assertEquals(ExtendedDriveStatus.SUCCESS, value.coordinator.sync(value.session, resume = true).status)
        assertEquals(1, value.api.creates)
        assertEquals(pending.id, value.api.files.values.single().id)
        assertArrayEquals(pending.bytes(), value.api.contents.getValue(pending.id))
        assertEquals(2, value.payloads.version)
        value.sync()
        assertEquals(2, value.api.creates)
        assertEquals(1, value.api.files.size)
    }

    @Test fun uncommittedUnknownCreateReplaysOnlyTheOriginalIdAndBody() = fixture { value ->
        value.enable()
        value.api.rejectBeforeCreate = true
        value.sync()
        val pending = checkNotNull(value.store.publication(OWNER, CHAPTERS))
        value.api.rejectBeforeCreate = false
        value.payloads.version = 7
        value.coordinator.sync(value.session, resume = true)
        assertEquals(pending.id, value.api.files.values.single().id)
        assertArrayEquals(pending.bytes(), value.api.contents.getValue(pending.id))
        assertEquals(7, value.payloads.version)
        assertEquals(1, value.api.generated)
    }

    @Test fun lostDeleteReplyRetainsBoundedPairUntilExplicitReadbackRecovery() = fixture { value ->
        value.enable()
        value.sync()
        value.payloads.version = 2
        value.api.loseDelete = true
        assertEquals(ExtendedDriveStatus.RECOVERY_REQUIRED, value.sync().lanes[CHAPTERS])
        value.payloads.version = 3
        value.sync()
        assertEquals(2, value.api.creates)
        value.recreate()
        value.coordinator.sync(value.session, resume = true)
        assertNull(value.store.publication(OWNER, CHAPTERS))
        assertEquals(1, value.api.files.size)
        value.sync()
        assertEquals(3, value.api.creates)
        assertEquals(1, value.api.files.size)
        assertEquals(2, value.api.peak)
    }

    @Test fun omittedProtectedMetadataPredecessorCannotBePublishedOrDeleted() = fixture { value ->
        value.enable(setOf(METADATA))
        value.sync()
        value.payloads.omitExport = true
        val before = value.api.files.values.single()
        val result = value.sync()
        assertEquals(ExtendedDriveStatus.REVIEW_REQUIRED, result.lanes[METADATA])
        assertEquals(listOf(before), value.api.files.values.toList())
        assertEquals(1, value.api.creates)
        assertEquals(0, value.api.deletes)
    }

    @Test fun unknownCreateCannotTransmitAfterPrivacyOrIdentityMergeBecomesInvalid() = fixture { value ->
        value.enable()
        value.api.rejectBeforeCreate = true
        value.sync()
        value.payloads.rejectMerge = true
        value.api.rejectBeforeCreate = false
        value.coordinator.sync(value.session, resume = true)
        assertEquals(1, value.api.creates)
        assertNotNull(value.store.publication(OWNER, CHAPTERS))
        assertTrue(value.api.files.isEmpty())
    }

    @Test fun differentOpaqueDriveAccountCannotReadOrReplayOldPublication() = fixture { value ->
        value.enable()
        value.api.rejectBeforeCreate = true
        value.sync()
        val previousCalls = value.api.calls
        val other = ExtendedDriveSession("another-opaque-owner", "other-token") {}
        assertEquals(ExtendedDriveStatus.STALE_SESSION, value.coordinator.sync(other, resume = true).status)
        assertEquals(previousCalls, value.api.calls)
        assertNotNull(value.store.publication(OWNER, CHAPTERS))
    }

    @Test fun anotherInstallationSnapshotIsMergedButNeverDeletedOrOverwritten() = fixture { value ->
        value.enable()
        val body = "{\"v\":4}".toByteArray()
        val descriptor = ExtendedDriveDescriptor(OWNER, java.util.UUID.randomUUID().toString(), "a".repeat(64),
            CHAPTERS, 1, ExtendedDriveProtocol.hash(body), body.size)
        value.api.insert("foreign-file", descriptor, body)
        value.sync()
        value.payloads.version = 8
        value.sync()
        assertNotNull(value.api.files["foreign-file"])
        assertArrayEquals(body, value.api.contents["foreign-file"])
        assertEquals(2, value.api.files.size)
        assertEquals(1, value.api.deletes)
    }

    @Test fun unexpectedOwnUuidOrWriterForkPausesWithoutMutation() = fixture { value ->
        value.enable()
        val identity = value.store.identity()
        val body = "{\"v\":1}".toByteArray()
        val descriptor = ExtendedDriveDescriptor(OWNER, identity.deviceId, "b".repeat(64), CHAPTERS,
            1, ExtendedDriveProtocol.hash(body), body.size)
        value.api.insert("foreign-own-uuid", descriptor, body)
        assertEquals(ExtendedDriveStatus.REVIEW_REQUIRED, value.sync().lanes[CHAPTERS])
        assertEquals(0, value.api.creates)
        assertEquals(0, value.api.deletes)
    }

    @Test fun sessionChangeDuringCreateLeavesJournalAndDoesNotCompact() = fixture { value ->
        value.enable()
        value.api.afterCreate = { value.active = false }
        assertEquals(ExtendedDriveStatus.STALE_SESSION, value.sync().status)
        assertNotNull(value.store.publication(OWNER, CHAPTERS))
        assertEquals(0, value.api.deletes)
    }

    @Test fun canceledCreateIsNotConvertedIntoRetryOrSuccess() = fixture { value ->
        value.enable()
        value.api.afterCreate = { throw CancellationException("Fixture cancellation") }
        try { value.sync(); fail("Cancellation must propagate") } catch (_: CancellationException) { Unit }
        assertEquals(1, value.api.creates)
        assertNotNull(value.store.publication(OWNER, CHAPTERS))
        assertEquals(0, value.api.deletes)
    }

    @Test fun immutableSuccessorChangedBeforeDeletePreservesOldFile() = fixture { value ->
        value.enable()
        value.sync()
        val first = value.api.files.values.single()
        value.payloads.version = 2
        value.payloads.afterValidation = {
            if (value.api.files.size == 2) {
                val current = value.api.files.values.maxBy { it.descriptor.generation }
                value.api.files.remove(current.id)
            }
        }
        value.sync()
        assertEquals(first, value.api.files[first.id])
        assertEquals(0, value.api.deletes)
        assertNotNull(value.store.publication(OWNER, CHAPTERS))
    }

    @Test fun checksumCorruptionFailsBeforeAnyReplayAndPreservesFile() = fixture { value ->
        value.enable()
        value.api.rejectBeforeCreate = true
        value.sync()
        val pending = File(value.store.directory, "publications").listFiles()!!.single { it.extension == "pending" }
        val bytes = pending.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); pending.writeBytes(bytes)
        assertEquals(ExtendedDriveStatus.REVIEW_REQUIRED, value.coordinator.sync(value.session, resume = true).status)
        assertArrayEquals(bytes, pending.readBytes())
        assertEquals(1, value.api.creates)
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        val directory = Files.createTempDirectory("extended-drive-fixture-").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }

    private class Fixture(private val directory: File) {
        var store = ExtendedDriveStore(directory)
        val api = FakeDrive()
        val payloads = Payloads()
        var active = true
        val session get() = ExtendedDriveSession(OWNER, "oauth-fixture-token") { check(active) }
        var coordinator = ExtendedDriveCoordinator(store, api, payloads)
        fun recreate() { store = ExtendedDriveStore(directory); coordinator = ExtendedDriveCoordinator(store, api, payloads) }
        fun enable(namespaces: Set<String> = setOf(CHAPTERS)) {
            store.configure(ExtendedDriveConfiguration(enabled = true, permissionId = OWNER, namespaces = namespaces))
        }
        suspend fun sync() = coordinator.sync(session)
    }

    internal class FakeDrive : ExtendedDriveTransport {
        val files = linkedMapOf<String, ExtendedDriveFile>()
        val contents = hashMapOf<String, ByteArray>()
        var calls = 0; var creates = 0; var generated = 0; var deletes = 0; var peak = 0
        var loseCreate = false; var loseDelete = false; var rejectBeforeCreate = false
        var afterCreate: () -> Unit = {}
        override suspend fun user(token: String, guard: () -> Unit): ExtendedDriveUser {
            calls++; guard(); return ExtendedDriveUser(OWNER, "Fixture", null)
        }
        override suspend fun list(session: ExtendedDriveSession): List<ExtendedDriveFile> {
            calls++; session.checkCurrent(); return files.values.toList()
        }
        override suspend fun generateId(session: ExtendedDriveSession): String {
            calls++; session.checkCurrent(); return "generated-${++generated}"
        }
        override suspend fun metadata(session: ExtendedDriveSession, id: String): ExtendedDriveFile? {
            calls++; session.checkCurrent(); return files[id]
        }
        override suspend fun download(session: ExtendedDriveSession, file: ExtendedDriveFile): ByteArray {
            calls++; session.checkCurrent(); check(files[file.id] == file); return contents.getValue(file.id).copyOf()
        }
        override suspend fun create(session: ExtendedDriveSession, id: String, descriptor: ExtendedDriveDescriptor,
            bytes: ByteArray) {
            calls++; creates++; session.checkCurrent()
            if (rejectBeforeCreate) throw IOException("Fixture connection lost before create")
            if (id in files) throw ExtendedDriveHttpException(409)
            insert(id, descriptor, bytes)
            afterCreate()
            if (loseCreate) { loseCreate = false; throw IOException("Fixture reply lost") }
        }
        fun insert(id: String, descriptor: ExtendedDriveDescriptor, bytes: ByteArray) {
            files[id] = ExtendedDriveFile(id, descriptor, "1", ExtendedDriveProtocol.md5(bytes))
            contents[id] = bytes.copyOf(); peak = maxOf(peak, files.size)
        }
        override suspend fun delete(session: ExtendedDriveSession, file: ExtendedDriveFile) {
            calls++; deletes++; session.checkCurrent(); check(files[file.id] == file)
            files.remove(file.id); contents.remove(file.id)
            if (loseDelete) { loseDelete = false; throw IOException("Fixture delete reply lost") }
        }
    }
    private class Payloads : ExtendedDrivePayloads {
        override val namespaces = ExtendedDriveProtocol.namespaces
        var version = 1; var omitExport = false; var rejectMerge = false
        var afterValidation: () -> Unit = {}
        override suspend fun export(namespace: String, checkCurrent: () -> Unit): ByteArray {
            checkCurrent(); return if (omitExport) "{}".toByteArray() else "{\"v\":$version}".toByteArray()
        }
        override suspend fun merge(namespace: String, bytes: ByteArray, checkCurrent: () -> Unit) {
            checkCurrent(); check(!rejectMerge)
            val root = ExtendedDriveProtocol.objectValue(bytes)
            val incoming = (root["v"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            checkCurrent(); version = maxOf(version, incoming)
        }
        override suspend fun validateSuccessor(namespace: String, predecessor: ByteArray, candidate: ByteArray) {
            val before = (ExtendedDriveProtocol.objectValue(predecessor)["v"] as? JsonPrimitive)?.content?.toInt() ?: 0
            val after = (ExtendedDriveProtocol.objectValue(candidate)["v"] as? JsonPrimitive)?.content?.toInt() ?: 0
            check(after >= before) { "Missing causal field" }; afterValidation()
        }
    }
    companion object {
        internal const val OWNER = "opaque-permission-owner"
        private const val CHAPTERS = "chapter_reads_v1"
        private const val METADATA = "metadata_overrides_v1"
    }
}
