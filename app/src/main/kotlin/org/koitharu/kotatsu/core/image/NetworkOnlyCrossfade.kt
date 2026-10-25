package org.koitharu.kotatsu.core.image

import coil3.decode.DataSource
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget

/** Cached/local images are ready to display; only newly fetched images need a loading transition. */
internal data class NetworkOnlyCrossfade(private val durationMillis: Int) : Transition.Factory {
    private val networkTransition = CrossfadeTransition.Factory(durationMillis)

    override fun create(target: TransitionTarget, result: ImageResult): Transition =
        if (result is SuccessResult && result.dataSource == DataSource.NETWORK) {
            networkTransition.create(target, result)
        } else {
            Transition.Factory.NONE.create(target, result)
        }
}
