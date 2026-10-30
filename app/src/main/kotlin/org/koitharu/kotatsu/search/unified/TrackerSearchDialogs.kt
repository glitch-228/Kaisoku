package org.koitharu.kotatsu.search.unified

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.redactSecrets
import org.koitharu.kotatsu.remote.library.LibraryDiagnostics

fun showTrackerSearchError(context: Context, error: Throwable) {
    val details = LibraryDiagnostics.text(error).redactSecrets()
    MaterialAlertDialogBuilder(context).setTitle(R.string.tracker_search_warnings)
        .setMessage(LibraryDiagnostics.message(error).redactSecrets())
        .setPositiveButton(R.string.copy) { _, _ -> context.copyToClipboard(context.getString(R.string.error), details) }
        .setNegativeButton(android.R.string.ok, null).show()
}
