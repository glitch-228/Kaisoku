package org.koitharu.kotatsu.core.archive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.koitharu.kotatsu.core.util.MimeTypes
import java.io.FileNotFoundException

/** Exported=false, no URI grants; private process/session tokens cannot be used by other applications. */
class ArchiveContentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Protected archive access is read-only")
        val context = requireNotNull(context)
        val target = ArchiveImageUris.capability(context, uri)
        return ArchiveProxyFileDescriptor.open(context, ArchiveAccess.get(context).openSeekable(target.file, target.entry))
    }

    override fun getType(uri: Uri): String? {
        val target = ArchiveImageUris.capability(requireNotNull(context), uri)
        return MimeTypes.getMimeTypeFromExtension(target.entry)?.toString() ?: "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val context = requireNotNull(context)
        val target = ArchiveImageUris.capability(context, uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = ArchiveAccess.get(context).entries(target.file).single { it.name == target.entry }.size
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> "protected image"; OpenableColumns.SIZE -> size; else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only provider")
}
