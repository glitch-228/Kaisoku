package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** Existing values gain causal ownership; missing fields never become invented reset records. */
class Migration37To38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE)
        val actor = UUID.randomUUID().toString()
        for ((field, column) in mapOf("title" to "title_override", "description" to "description_override",
            "cover" to "cover_override", "content_rating" to "content_rating_override")) {
            db.execSQL("INSERT INTO metadata_override_state(manga_id,source,edition_url,field_key,field_value," +
                "asset_sha256,revision_wall,revision_logical,revision_actor) " +
                "SELECT p.manga_id,m.source,m.url,?,p.$column,NULL,0,0,? FROM preferences p " +
                "JOIN manga m ON m.manga_id=p.manga_id WHERE p.$column IS NOT NULL AND m.url!='' " +
                "AND m.source!='' ORDER BY p.manga_id", arrayOf(field, actor))
        }
    }

    companion object {
        const val CREATE = "CREATE TABLE IF NOT EXISTS metadata_override_state (" +
            "manga_id INTEGER NOT NULL,source TEXT NOT NULL,edition_url TEXT NOT NULL,field_key TEXT NOT NULL," +
            "field_value TEXT,asset_sha256 TEXT,revision_wall INTEGER NOT NULL,revision_logical INTEGER NOT NULL," +
            "revision_actor TEXT NOT NULL,PRIMARY KEY(manga_id,source,edition_url,field_key))"
    }
}
