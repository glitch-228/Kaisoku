package org.koitharu.kotatsu.core.image

import android.content.Context
import coil3.Extras
import coil3.decode.Decoder
import coil3.fetch.Fetcher
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.size.ViewSizeResolver
import kotlin.reflect.KClass

/** Compare image behavior without treating a freshly allocated weak view target as different content. */
internal data class ViewImageRequestKey(
    val context: Context,
    val data: Any,
    val extras: Extras,
    val decoder: Decoder.Factory?,
    val fetcher: Pair<Fetcher.Factory<*>, KClass<*>>?,
    val exactSize: Size?,
    val sizeResolver: Any,
    val scale: Scale,
    val precision: Precision,
    val memoryKey: String?,
    val memoryKeyExtras: Map<String, String>,
    val diskKey: String?,
    val memoryPolicy: CachePolicy,
    val diskPolicy: CachePolicy,
    val networkPolicy: CachePolicy,
    val listener: ImageRequest.Listener?,
) {
    companion object {
        fun from(request: ImageRequest, exactSize: Size?) = ViewImageRequestKey(
            context = request.context,
            data = request.data,
            extras = request.extras,
            decoder = request.decoderFactory,
            fetcher = request.fetcherFactory,
            exactSize = exactSize,
            sizeResolver = request.sizeResolver.let { if (it is ViewSizeResolver<*>) it.javaClass else it },
            scale = request.scale,
            precision = request.precision,
            memoryKey = request.memoryCacheKey,
            memoryKeyExtras = request.memoryCacheKeyExtras,
            diskKey = request.diskCacheKey,
            memoryPolicy = request.memoryCachePolicy,
            diskPolicy = request.diskCachePolicy,
            networkPolicy = request.networkCachePolicy,
            listener = request.listener,
        )
    }
}
