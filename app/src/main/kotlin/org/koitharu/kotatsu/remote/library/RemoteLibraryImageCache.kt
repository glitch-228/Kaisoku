package org.koitharu.kotatsu.remote.library

import android.content.Context
import android.content.ContextWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.local.data.CacheDir
import org.koitharu.kotatsu.local.data.LocalStorageCache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Uses the existing bounded cache backend, exclusively in app-private internal storage. */
@Singleton
class RemoteLibraryImageCache @Inject constructor(@ApplicationContext context: Context) {
    private val scopedContext = object : ContextWrapper(context) {
        override fun getExternalCacheDirs(): Array<File> = emptyArray()
        override fun getCacheDir(): File = File(super.getCacheDir(), "remote_library_images").apply {
            if (!isDirectory && !mkdirs()) throw RemoteLibraryException("Private library image cache is unavailable")
        }
    }
    val cache = LocalStorageCache(scopedContext, CacheDir.PAGES, 96L * 1024 * 1024, 8L * 1024 * 1024)
}
