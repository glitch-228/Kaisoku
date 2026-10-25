package org.koitharu.kotatsu.core.archive

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.cancel
import org.koitharu.kotatsu.core.image.CoilImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/** No state transfer: decrypted display data is discarded, while the library/reader remain untouched. */
internal fun retireArchiveDisplay(activity: FragmentActivity, root: View, clearModel: () -> Unit) {
    root.alpha = 0f
    root.visibility = View.INVISIBLE
    activity.lifecycleScope.cancel()
    clearModel()
    for (fragment in activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()) {
        fragment.dialog?.window?.decorView?.let(::clearArchiveDisplayViews)
        fragment.dismissAllowingStateLoss()
    }
    clearArchiveDisplayViews(root)
    activity.viewModelStore.clear()
    activity.finish()
}

internal fun clearArchiveDisplayViews(view: View) {
    view.contentDescription = null
    when (view) {
        is TextView -> view.text = null
        is CoilImageView -> view.disposeImage()
        is ImageView -> view.setImageDrawable(null)
        is SubsamplingScaleImageView -> view.recycle()
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) clearArchiveDisplayViews(view.getChildAt(index))
    }
    if (view is RecyclerView) {
        view.adapter = null
        view.recycledViewPool.clear()
    }
}
