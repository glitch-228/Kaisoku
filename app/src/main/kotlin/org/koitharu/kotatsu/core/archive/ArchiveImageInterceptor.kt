package org.koitharu.kotatsu.core.archive

import android.net.Uri
import androidx.core.net.toUri
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.MangaPage
import java.io.File

/** Run before Coil's cache lookup; protected inputs never enter global disk or memory caches. */
class ArchiveImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val uri = when (data) {
            is Uri -> data
            is coil3.Uri -> data.toAndroidUri()
            is File -> data.toUri()
            is String -> data.toUri()
            is MangaPage -> data.url.toUri()
            else -> null
        } ?: return chain.proceed()
        if (!withContext(Dispatchers.IO) { ArchiveImageUris.isSensitive(chain.request.context, uri) }) return chain.proceed()
        val request = chain.request.newBuilder().diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build()
        return chain.withRequest(request).proceed()
    }
}
