package org.koitharu.kotatsu.core.archive

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.content.getSystemService
import java.io.IOException

/** Native seek/read bridge; plaintext remains in bounded callback buffers, never a filesystem path. */
object ArchiveProxyFileDescriptor {
    private val readHandler by lazy { Handler(HandlerThread("protected-archive-reads").apply { start() }.looper) }
    fun open(context: Context, entry: SeekableArchiveEntry): ParcelFileDescriptor {
        val storage = context.getSystemService<StorageManager>() ?: throw IOException("Storage service is unavailable")
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = entry.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                entry.readAt(offset, data, 0, minOf(size, data.size)).coerceAtLeast(0)
            } catch (_: IOException) {
                data.fill(0)
                throw ErrnoException("read protected archive", OsConstants.EACCES)
            }

            override fun onRelease() { entry.close() }
        }
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, readHandler)
        } catch (e: Exception) { entry.close(); throw e }
    }
}
