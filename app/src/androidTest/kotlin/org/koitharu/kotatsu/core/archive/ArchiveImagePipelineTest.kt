package org.koitharu.kotatsu.core.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.image.ArchiveBitmapDecoder
import org.koitharu.kotatsu.core.image.CbzFetcher
import org.koitharu.kotatsu.core.image.ReaderRegionDecoderFactory
import org.koitharu.kotatsu.core.util.ext.toZipUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Actual Android bitmap/proxy/Coil paths. These fixtures never use provider accounts or network. */
class ArchiveImagePipelineTest {
    @Test fun protectedThumbnailsBypassExistingMemoryEntriesAndNeverWriteDiskCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "protected-image-pipeline-${UUID.randomUUID()}").apply { mkdir() }
        val file = File(directory, "private.cbz")
        val archives = ArchiveAccess.get(context)
        val password = "fixture password".toCharArray()
        val stale = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val memory = MemoryCache.Builder().maxSizeBytes(1024 * 1024).build()
        val disk = DiskCache.Builder().directory(File(directory, "coil")).maxSizeBytes(1024 * 1024).build()
        val loader = ImageLoader.Builder(context).memoryCache(memory).diskCache(disk).components {
            add(ArchiveImageInterceptor())
            add(CbzFetcher.Factory())
        }.build()
        try {
            write(file, password)
            archives.unlock(file, password)
            val uri = file.toZipUri("private/page.png")
            val key = "fixture-private-cover"
            memory[MemoryCache.Key(key)] = MemoryCache.Value(stale.asImage())
            val request = ImageRequest.Builder(context).data(uri).allowHardware(false)
                .memoryCacheKey(key).diskCacheKey(key).build()
            val result = loader.execute(request)
            assertTrue(result.toString(), result is SuccessResult)
            val decoded = (result as SuccessResult).image.toBitmap()
            assertEquals(64, decoded.width)
            assertEquals(32, decoded.height)
            assertEquals(Color.GREEN, decoded.getPixel(4, 4))
            assertEquals(3, memory[MemoryCache.Key(key)]!!.image.width)
            assertNull(disk.openSnapshot(key))
            archives.sessions.lock(file)
            assertTrue(loader.execute(request) is ErrorResult)
            assertNull(disk.openSnapshot(key))
            assertTrue(file.isFile)
        } finally {
            loader.shutdown()
            disk.shutdown()
            memory.clear()
            archives.sessions.lock(file)
            password.fill('\u0000')
            if (!stale.isRecycled) stale.recycle()
            directory.deleteRecursively()
        }
    }

    @Test fun tiledReaderAndWholeImageConsumersUseTheSameRevocableArchiveBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("protected-reader-pipeline-", ".cbz", context.cacheDir)
        val password = "fixture password".toCharArray()
        val archives = ArchiveAccess.get(context)
        val decoder = ReaderRegionDecoderFactory(Bitmap.Config.ARGB_8888, pooled = false).make()
        try {
            write(file, password)
            val original = file.readBytes()
            archives.unlock(file, password)
            val identity = file.toZipUri("private/page.png")
            val capability = ArchiveImageUris.resolve(context, identity)
            val size = decoder.init(context, capability)
            assertEquals(64, size.x)
            assertEquals(32, size.y)
            decoder.decodeRegion(Rect(8, 4, 48, 28), 1).let { tile ->
                try {
                    assertEquals(40, tile.width)
                    assertEquals(24, tile.height)
                    assertEquals(Color.GREEN, tile.getPixel(0, 0))
                    assertEquals(Color.BLUE, tile.getPixel(39, 23))
                } finally { tile.recycle() }
            }
            ArchiveBitmapDecoder.decode(context, capability).let { bitmap ->
                try {
                    assertEquals(size.x, bitmap.width)
                    assertEquals(size.y, bitmap.height)
                    assertEquals(Color.BLUE, bitmap.getPixel(63, 31))
                } finally { bitmap.recycle() }
            }
            assertEquals(identity, ArchiveImageUris.original(context, capability))
            // Reader holders recycle their native decoder when the lock listener closes the activity.
            decoder.recycle()
            archives.sessions.lock(file)
            assertTrue(runCatching { ArchiveBitmapDecoder.decode(context, capability) }.isFailure)
            assertTrue(runCatching { ArchiveImageUris.resolve(context, identity) }.exceptionOrNull()
                is ArchiveAccessException.Locked)
            assertTrue(original.contentEquals(file.readBytes()))
            assertFalse(file.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("${file.name}.plaintext") })
        } finally {
            decoder.recycle()
            archives.sessions.lock(file)
            password.fill('\u0000')
            file.delete()
        }
    }

    private fun write(file: File, password: CharArray) {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.GREEN)
            for (y in 16 until 32) for (x in 32 until 64) bitmap.setPixel(x, y, Color.BLUE)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        try {
            ZipOutputStream(file.outputStream(), password).use { zip ->
                zip.putNextEntry(ZipParameters().apply {
                    fileNameInZip = "private/page.png"
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                })
                zip.write(bytes)
                zip.closeEntry()
            }
        } finally { bytes.fill(0) }
    }
}
