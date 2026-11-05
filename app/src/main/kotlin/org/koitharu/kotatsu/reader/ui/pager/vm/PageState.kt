package org.koitharu.kotatsu.reader.ui.pager.vm

import android.net.Uri
import com.davemorrissey.labs.subscaleview.ImageSource

sealed class PageState {

    data object Empty : PageState()

    data class Loading(
        val preview: ImageSource?,
        val progress: Int,
    ) : PageState()

    data class Loaded(
        val source: ImageSource,
        val isConverted: Boolean,
        val animationUri: Uri? = null,
    ) : PageState() {
        val isAnimated: Boolean get() = animationUri != null
    }

    class Converting() : PageState()

    /** A page whose file is one the reader hid (beta). The slot stays, so page numbers and positions do not move. */
    data object Hidden : PageState()

    data class Shown(
        val source: ImageSource,
        val isConverted: Boolean,
        val animationUri: Uri? = null,
    ) : PageState() {
        val isAnimated: Boolean get() = animationUri != null
    }

    data class Error(
        val error: Throwable,
    ) : PageState()

    fun isFinalState(): Boolean = this is Error || this is Shown || this is Hidden
}
