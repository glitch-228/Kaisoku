package org.koitharu.kotatsu.core.image

import android.view.View
import coil3.size.ViewSizeResolver
import kotlinx.coroutines.CancellationException
import java.lang.ref.WeakReference

/** A queued or slowly cancelling image request must not own its view through the size resolver. */
internal class WeakViewSizeResolver<T : View>(view: T) : ViewSizeResolver<T> {

	private val viewRef = WeakReference(view)

	override val view: T
		get() = viewRef.get() ?: throw CancellationException("Image view was released")
}
