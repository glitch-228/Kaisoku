package org.koitharu.kotatsu.backups.webdav

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.reader.data.TapGridSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Private fixtures and in-memory DBs only. No production DB, account, external DAV server or source is used. */
@HiltAndroidTest
class WebDavNativeBackupTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var tapGrid: TapGridSettings
    @Inject lateinit var sources: MangaSourcesRepository
    @Inject lateinit var filters: SavedFiltersRepository

    @Before fun inject() = hilt.inject()

    @Test fun actualNativeBackupPublishesDownloadsAndRestoresWithoutChangingOriginalResumeOrSourceIdentity() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "webdav-native-${UUID.randomUUID()}").apply { mkdirs() }
        val context = isolatedContext(app, directory)
        try {
            NativeDavFixture().use { server ->
                val connection = server.connection()
                val journal = object : WebDavOwnershipJournal {
                    private var records = emptyList<WebDavOwnedBackup>()
                    override suspend fun read(connection: WebDavConnection) = records
                    override suspend fun write(connection: WebDavConnection, records: List<WebDavOwnedBackup>) {
                        this.records = records
                    }
                }
                withDatabase(context) { original ->
                    val manga = MangaBackup(983301, "DAV native fixture", url = "/book", publicUrl = "https://fixture.invalid/book",
                        coverUrl = "", source = "mihon:fixture-source")
                    val resume = HistoryEntity(manga.id, 10L, 20L, 983302L, 7, 321f, .37f, 0L, 27)
                    original.getMangaDao().upsert(manga.toEntity(), emptyList())
                    original.getHistoryDao().upsert(resume)
                    org.koitharu.kotatsu.sync.drive.DriveReplicaJournal.install(original.openHelper.writableDatabase)
                    val repository = BackupRepository(context, original, AppSettings(context), tapGrid, sources, filters)
                    val sections = setOf(BackupSection.INDEX, BackupSection.HISTORY)
                    val archive = File(directory, "created.bk.zip")
                    ZipOutputStream(archive.outputStream()).use { zip -> repository.createBackup(zip, null, sections) }
                    assertEquals(sections, WebDavBackupFiles.validate(archive))
                    val transport = WebDavTransport(connection)
                    val published = WebDavPublisher(connection, transport, journal).upload(archive)
                    assertTrue(journal.read(connection).single().published)
                    assertEquals(listOf(published.backup.name), transport.list().map { it.name })
                    val downloaded = File(directory, "downloaded.bk.zip")
                    transport.download(published.backup, downloaded)
                    assertArrayEquals(archive.readBytes(), downloaded.readBytes())
                    assertEquals(sections, WebDavBackupFiles.validate(downloaded))
                    withDatabase(context) { restored ->
                        org.koitharu.kotatsu.sync.drive.DriveReplicaJournal.install(restored.openHelper.writableDatabase)
                        val destination = BackupRepository(context, restored, AppSettings(context), tapGrid, sources, filters)
                        val result = ZipInputStream(downloaded.inputStream()).use { zip ->
                            destination.restoreBackup(zip, sections, null, isMerge = true)
                        }
                        assertTrue(result.failures.toString(), result.isAllSuccess)
                        assertEquals(resume, restored.getHistoryDao().find(manga.id))
                        assertEquals(manga.source, restored.getMangaDao().find(manga.id)?.manga?.source)
                        assertTrue(restored.getFavouritesDao().findAll().isEmpty())
                    }
                    assertEquals(resume, original.getHistoryDao().find(manga.id))
                    assertEquals(manga.source, original.getMangaDao().find(manga.id)?.manga?.source)
                    assertEquals(1, server.methods.count { it == "PUT" })
                    assertEquals(1, server.methods.count { it == "MOVE" })
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun protectedConfigurationRecreatesAndKeepsCredentialsOwnershipAndCooldownOutsideOrdinaryPreferences() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(app.cacheDir, "webdav-protected-${UUID.randomUUID()}").apply { mkdirs() }
        val context = isolatedContext(app, directory)
        try {
            val secret = "long-private-fixture-password-${UUID.randomUUID()}"
            val connection = WebDavConnection(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Private fixture",
                WebDavEndpoint.parse("https://fixture.invalid/dav/"), "fixture-user", secret)
            val first = WebDavConfigurationStore(context)
            first.save(connection)
            first.recordCooldown(connection, System.currentTimeMillis() + 120_000L)
            val recreated = WebDavConfigurationStore(context)
            val restored = checkNotNull(recreated.connection())
            assertEquals(connection.id, restored.id)
            assertEquals(connection.revision, restored.revision)
            assertEquals(secret, restored.password)
            assertEquals(0, restored.scheduleHours)
            assertEquals(0, restored.keepBackups)
            assertTrue(recreated.remainingCooldown(restored, System.currentTimeMillis()) > 90_000L)
            assertTrue(AppSettings(context).getAllValues().keys.none { it.contains("webdav", ignoreCase = true) })
            directory.walkTopDown().filter(File::isFile).forEach { file ->
                assertFalse("Plaintext password in ${file.name}", file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
            }
            val updated = WebDavConnection(restored.id, UUID.randomUUID().toString(), restored.displayName,
                restored.endpoint, restored.username, restored.password)
            recreated.save(updated)
            val stale = runCatching { recreated.requireCurrent(restored) }.exceptionOrNull()
            assertTrue(stale is CancellationException)
            recreated.disconnect()
            assertNull(recreated.connection())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun isolatedContext(app: Context, directory: File): Context {
        val prefix = "webdav-${UUID.randomUUID()}-"
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(directory, "private").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(directory, "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
    }

    private suspend fun <T> withDatabase(context: Context, action: suspend (MangaDatabase) -> T): T {
        val database = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        return try { action(database) } finally { database.close() }
    }
}

/** Small HTTP fixture usable both on Android and in the root-owned framework host harness. */
private class NativeDavFixture : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean()
    private val executor = Executors.newFixedThreadPool(3) { task -> Thread(task, "native-dav-client").apply { isDaemon = true } }
    private val files = Collections.synchronizedMap(linkedMapOf<String, ByteArray>())
    val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val acceptor = Thread({
        while (!closed.get()) {
            val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
            executor.execute { client.use(::handle) }
        }
    }, "native-dav-fixture").apply { isDaemon = true; start() }

    fun connection() = WebDavConnection(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Native fixture",
        WebDavEndpoint.parse("http://127.0.0.1:${socket.localPort}/dav/", true), "fixture", "fixture-password", true)

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream()
        val header = ByteArrayOutputStream()
        while (header.size() <= 16 * 1024) {
            val value = input.read()
            if (value < 0) return
            header.write(value)
            val bytes = header.toByteArray()
            if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
        }
        check(header.size() <= 16 * 1024)
        val lines = header.toString("ISO-8859-1").split("\r\n")
        val first = lines.first().split(' ')
        val method = first[0]
        val name = URI(first[1]).path.removePrefix("/dav/")
        val headers = lines.drop(1).filter { ':' in it }.associate {
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        }
        methods += method
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        check(length in 0..1024 * 1024)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) return
            offset += count
        }
        var status = 200
        val response = when (method) {
            "PROPFIND" -> {
                if (name.isNotEmpty() && !files.containsKey(name)) { status = 404; byteArrayOf() }
                else {
                    status = 207
                    val values = if (name.isEmpty()) synchronized(files) { files.toMap() } else mapOf(name to files.getValue(name))
                    buildString {
                        append("<d:multistatus xmlns:d=\"DAV:\">")
                        values.forEach { (key, value) ->
                            append("<d:response><d:href>/dav/").append(key).append("</d:href><d:propstat><d:prop>")
                            append("<d:getcontentlength>").append(value.size).append("</d:getcontentlength>")
                            append("<d:getetag>\"native-1\"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                        }
                        append("</d:multistatus>")
                    }.toByteArray()
                }
            }
            "PUT" -> {
                if (files.containsKey(name)) status = 412 else { files[name] = body; status = 201 }
                byteArrayOf()
            }
            "MOVE" -> {
                val destination = URI(checkNotNull(headers["destination"])).path.removePrefix("/dav/")
                val original = files[name]
                when {
                    headers["overwrite"] != "F" -> status = 400
                    original == null -> status = 404
                    files.containsKey(destination) -> status = 412
                    else -> { files[destination] = original; files.remove(name); status = 201 }
                }
                byteArrayOf()
            }
            "GET" -> files[name] ?: byteArrayOf().also { status = 404 }
            else -> byteArrayOf().also { status = 405 }
        }
        client.getOutputStream().use { output ->
            output.write("HTTP/1.1 $status Fixture\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1))
            output.write(response)
        }
    }

    override fun close() {
        closed.set(true)
        socket.close()
        executor.shutdownNow()
        acceptor.join(2_000)
    }
}
