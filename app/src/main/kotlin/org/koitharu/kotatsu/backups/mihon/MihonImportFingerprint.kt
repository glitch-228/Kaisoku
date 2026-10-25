package org.koitharu.kotatsu.backups.mihon

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koitharu.kotatsu.core.db.MangaDatabase
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Call within the same Room transaction as the reviewed read or commit. Never hash credentials. */
internal suspend fun MangaDatabase.mihonImportFingerprint(ids: Set<Long>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = DataOutputStream(DigestOutputStream(object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }, digest))
    val connection = openHelper.writableDatabase
    val chunks = ids.sorted().chunked(500)
    val tables = listOf("manga", "manga_tags", "preferences", "chapters", "history", "favourites",
        "favorite_order", "bookmarks", "scrobblings", "chapter_read_policy", "chapter_read_record",
        "chapter_read_alias", "chapter_read_count", "tracker_links_v2", "tracker_import_pending", "foreign_import_receipt")
    var visited = 0
    for (table in tables + "favourite_categories") {
        sink.cell(table.toByteArray())
        val batches = if (table == "favourite_categories") listOf(emptyList()) else chunks
        for (batch in batches) {
            currentCoroutineContext().ensureActive()
            val where = if (batch.isEmpty()) "" else " WHERE manga_id IN (${batch.joinToString { "?" }})"
            connection.query(SimpleSQLiteQuery("SELECT * FROM `$table`$where ORDER BY rowid", batch.toTypedArray())).use { cursor ->
                cursor.columnNames.forEach { sink.cell(it.toByteArray()) }
                while (cursor.moveToNext()) {
                    if (++visited % 128 == 0) currentCoroutineContext().ensureActive()
                    sink.writeByte(127)
                    for (column in 0 until cursor.columnCount) {
                        val type = cursor.getType(column)
                        sink.writeByte(type)
                        when (type) {
                            Cursor.FIELD_TYPE_NULL -> Unit
                            Cursor.FIELD_TYPE_INTEGER -> sink.writeLong(cursor.getLong(column))
                            Cursor.FIELD_TYPE_FLOAT -> sink.writeDouble(cursor.getDouble(column))
                            Cursor.FIELD_TYPE_BLOB -> sink.cell(cursor.getBlob(column))
                            else -> sink.cell(cursor.getString(column).toByteArray())
                        }
                    }
                }
            }
        }
    }
    sink.flush()
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun DataOutputStream.cell(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
}
