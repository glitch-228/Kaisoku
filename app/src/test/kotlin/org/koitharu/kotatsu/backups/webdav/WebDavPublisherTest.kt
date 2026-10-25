package org.koitharu.kotatsu.backups.webdav

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class WebDavPublisherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun uploadJournalsBeforePutAndPublishesOnlyVerifiedBytesWithoutOverwritingExistingBackups() = runTest {
        WebDavFixture().use { fixture ->
            val journal = MemoryWebDavJournal()
            val original = fixture.put("previous.bk.zip", "keep".toByteArray())
            fixture.handler = { exchange ->
                if (exchange.requestMethod == "PUT") {
                    assertEquals(1, journal.records.size)
                    assertFalse(journal.records.single().published)
                }
                false
            }
            val file = temporary.newFile().apply { writeText("native-backup") }
            val result = WebDavPublisher(fixture.connection, WebDavTransport(fixture.connection), journal).upload(file)
            assertEquals("native-backup", fixture.objects.getValue(result.backup.name).bytes.toString(Charsets.UTF_8))
            assertEquals("keep", fixture.objects.getValue(original.name).bytes.toString(Charsets.UTF_8))
            assertEquals(1, fixture.calls.count { it.method == "PUT" })
            assertEquals(1, fixture.calls.count { it.method == "MOVE" })
            assertTrue(journal.records.single().published)
            assertEquals(result.backup.strongEtag, journal.records.single().verifiedEtag)
            assertTrue(fixture.objects.keys.none { it.endsWith(".part") })
            assertNull(result.retainedWarning)
        }
    }

    @Test fun committedMoveWithLostSuccessResponseIsReadBackWithoutRepeatingPutOrMove() = runTest {
        WebDavFixture().use { fixture ->
            fixture.moveMode = "moved-error"
            val journal = MemoryWebDavJournal()
            val result = WebDavPublisher(fixture.connection, WebDavTransport(fixture.connection), journal)
                .upload(temporary.newFile().apply { writeText("backup") })
            assertTrue(fixture.objects.containsKey(result.backup.name))
            assertTrue(journal.records.single().published)
            assertEquals(1, fixture.calls.count { it.method == "MOVE" })
            assertEquals(1, fixture.calls.count { it.method == "PUT" })
        }
    }

    @Test fun identicalDestinationCollisionNeverGrantsOwnershipOrDeletesTheOtherBackup() = runTest {
        WebDavFixture().use { fixture ->
            fixture.moveMode = "collision-identical"
            val journal = MemoryWebDavJournal()
            val failure = runCatching {
                WebDavPublisher(fixture.connection, WebDavTransport(fixture.connection), journal)
                    .upload(temporary.newFile().apply { writeText("same bytes") })
            }.exceptionOrNull()
            assertTrue(failure is WebDavFailure && failure.status == 412)
            assertTrue(journal.records.isEmpty())
            assertEquals(1, fixture.objects.size)
            assertTrue(WebDavEndpoint.isNativeBackup(fixture.objects.keys.single()))
            assertTrue(fixture.calls.filter { it.method == "DELETE" }.all { it.path.endsWith(".part") })
        }
    }

    @Test fun unsupportedMovePreservesOldBackupAndOnlyConditionallyRemovesItsOwnStagingObject() = runTest {
        WebDavFixture().use { fixture ->
            fixture.moveMode = "unsupported"
            fixture.put("previous.bk.zip", "old".toByteArray())
            val journal = MemoryWebDavJournal()
            val failure = runCatching {
                WebDavPublisher(fixture.connection, WebDavTransport(fixture.connection), journal)
                    .upload(temporary.newFile().apply { writeText("new") })
            }.exceptionOrNull()
            assertTrue(failure is WebDavFailure && failure.status == 405)
            assertEquals(setOf("previous.bk.zip"), fixture.objects.keys)
            assertTrue(journal.records.isEmpty())
            assertTrue(fixture.calls.filter { it.method == "DELETE" }.all { it.condition?.startsWith('"') == true })
        }
    }

    @Test fun unknownMoveStaysPendingUntilReadbackAndIsNeverBlindlyRepeated() = runTest {
        WebDavFixture().use { fixture ->
            fixture.moveMode = "unknown-unmoved"
            val journal = MemoryWebDavJournal()
            val publisher = WebDavPublisher(fixture.connection, WebDavTransport(fixture.connection), journal)
            val file = temporary.newFile().apply { writeText("backup") }
            assertTrue(runCatching { publisher.upload(file) }.exceptionOrNull() is WebDavPublicationUncertain)
            assertEquals(1, fixture.calls.count { it.method == "PUT" })
            assertEquals(1, fixture.calls.count { it.method == "MOVE" })
            assertEquals(1, journal.records.size)
            assertFalse(journal.records.single().published)
            fixture.moveMode = "normal"
            publisher.reconcilePending()
            assertTrue(journal.records.isEmpty())
            assertTrue(fixture.objects.isEmpty())
            assertEquals(1, fixture.calls.count { it.method == "MOVE" })
            assertTrue(fixture.calls.any { it.method == "DELETE" && it.condition != null })
        }
    }

    @Test fun retentionUsesOwnershipStrongEtagsAndHashAndKeepsModifiedForeignAndLatestObjects() = runTest {
        WebDavFixture().use { fixture ->
            val config = withRetention(fixture.connection, 2)
            val journal = MemoryWebDavJournal()
            val owned = (1..3).map { index ->
                val resource = fixture.put("owned-$index.bk.zip", "old-$index".toByteArray())
                val file = temporary.newFile().apply { writeText("old-$index") }
                WebDavOwnedBackup(resource.name, ".${resource.name}.part", resource.size,
                    WebDavPublisher.hashFile(file), index.toLong(), resource.strongEtag, true)
            }
            journal.records = owned
            fixture.put(owned.first().name, "replacement".toByteArray())
            fixture.put("kaisoku_another-device.bk.zip", "foreign".toByteArray())
            val result = WebDavPublisher(config, WebDavTransport(config), journal)
                .upload(temporary.newFile().apply { writeText("latest") })
            assertTrue(fixture.objects.containsKey(owned[0].name))
            assertFalse(fixture.objects.containsKey(owned[1].name))
            assertTrue(fixture.objects.containsKey(owned[2].name))
            assertTrue(fixture.objects.containsKey("kaisoku_another-device.bk.zip"))
            assertTrue(fixture.objects.containsKey(result.backup.name))
            assertEquals(listOf("/dav/${owned[1].name}"), fixture.calls.filter { it.method == "DELETE" }.map { it.path })
        }
    }

    @Test fun retentionConditionalDeleteProtectsAFileChangedAfterVerification() = runTest {
        WebDavFixture().use { fixture ->
            val journal = MemoryWebDavJournal()
            val file = temporary.newFile().apply { writeText("old") }
            journal.records = (1..3).map { index ->
                val item = fixture.put("old-$index.bk.zip", file.readBytes())
                WebDavOwnedBackup(item.name, ".${item.name}.part", item.size, WebDavPublisher.hashFile(file),
                    index.toLong(), item.strongEtag, true)
            }
            fixture.beforeDelete = { name -> fixture.put(name, "changed concurrently".toByteArray()) }
            WebDavPublisher(withRetention(fixture.connection, 2), WebDavTransport(fixture.connection), journal).retain()
            assertEquals(3, fixture.objects.size)
            assertEquals(3, journal.records.size)
            assertEquals("changed concurrently", fixture.objects.getValue("old-1.bk.zip").bytes.toString(Charsets.UTF_8))
        }
    }

    @Test fun pendingObjectWithWrongBytesIsNeverDeletedOrClaimed() = runTest {
        WebDavFixture().use { fixture ->
            val journal = MemoryWebDavJournal()
            val file = temporary.newFile().apply { writeText("ours") }
            val record = WebDavOwnedBackup("pending.bk.zip", ".pending.bk.zip.part", file.length(),
                WebDavPublisher.hashFile(file), 1L)
            journal.records = listOf(record)
            fixture.put(record.stagingName, "them".toByteArray())
            assertTrue(runCatching {
                WebDavPublisher(fixture.connection, WebDavTransport(fixture.connection), journal).reconcilePending()
            }.exceptionOrNull() is WebDavPublicationUncertain)
            assertTrue(fixture.calls.none { it.method == "DELETE" || it.method == "MOVE" || it.method == "PUT" })
            assertEquals(listOf(record), journal.records)
        }
    }

    @Test fun retentionNeverRemovesTheJustPublishedBackupAfterWallClockRollback() = runTest {
        WebDavFixture().use { fixture ->
            val connection = withRetention(fixture.connection, 2)
            val journal = MemoryWebDavJournal()
            journal.records = (1..3).map { index ->
                val file = temporary.newFile().apply { writeText("old-$index") }
                val resource = fixture.put("clock-$index.bk.zip", file.readBytes())
                WebDavOwnedBackup(resource.name, ".${resource.name}.part", resource.size,
                    WebDavPublisher.hashFile(file), 1000L + index, resource.strongEtag, true, sequence = index.toLong())
            }
            val result = WebDavPublisher(connection, WebDavTransport(connection), journal, clock = { 2L })
                .upload(temporary.newFile().apply { writeText("new after clock rollback") })
            assertTrue(fixture.objects.containsKey(result.backup.name))
            assertEquals(4L, journal.records.single { it.name == result.backup.name }.sequence)
            assertEquals(setOf("clock-3.bk.zip", result.backup.name), fixture.objects.keys)
        }
    }

    @Test fun serverErrorWithoutConfirmedFinalRemainsPendingAndDoesNotDeleteOrRepeatWrites() = runTest {
        WebDavFixture().use { fixture ->
            fixture.moveMode = "error-unmoved"
            val journal = MemoryWebDavJournal()
            val failure = runCatching {
                WebDavPublisher(fixture.connection, WebDavTransport(fixture.connection), journal)
                    .upload(temporary.newFile().apply { writeText("backup") })
            }.exceptionOrNull()
            assertTrue(failure is WebDavPublicationUncertain)
            assertEquals(1, journal.records.size)
            assertTrue(fixture.calls.none { it.method == "DELETE" })
            assertEquals(1, fixture.calls.count { it.method == "MOVE" })
            assertEquals(1, fixture.calls.count { it.method == "PUT" })
        }
    }

    private fun withRetention(connection: WebDavConnection, keep: Int) = WebDavConnection(connection.id,
        connection.revision, connection.displayName, connection.endpoint, connection.username, connection.password,
        connection.allowCleartextLan, keepBackups = keep)
}
