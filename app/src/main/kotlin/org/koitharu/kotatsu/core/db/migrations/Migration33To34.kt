package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration33To34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SQL.forEach(db::execSQL)
    }

    companion object {
        val SQL = listOf(
            """CREATE TABLE IF NOT EXISTS chapter_read_policy (manga_id INTEGER NOT NULL, source TEXT NOT NULL, edition_url TEXT NOT NULL, generation TEXT NOT NULL, enabled INTEGER NOT NULL, deleted INTEGER NOT NULL, progress_branch TEXT NOT NULL, revision_wall INTEGER NOT NULL, revision_logical INTEGER NOT NULL, revision_actor TEXT NOT NULL, import_fingerprint TEXT, generation_wall INTEGER NOT NULL DEFAULT 0, generation_logical INTEGER NOT NULL DEFAULT 0, generation_actor TEXT NOT NULL DEFAULT 'unknown', PRIMARY KEY(manga_id))""",
            """CREATE TABLE IF NOT EXISTS chapter_read_record (manga_id INTEGER NOT NULL, generation TEXT NOT NULL, reference_id TEXT NOT NULL, source TEXT NOT NULL, branch_key TEXT NOT NULL, chapter_id INTEGER NOT NULL, original_url TEXT NOT NULL, is_read INTEGER NOT NULL, revision_wall INTEGER NOT NULL, revision_logical INTEGER NOT NULL, revision_actor TEXT NOT NULL, PRIMARY KEY(manga_id,generation,reference_id))""",
            """CREATE TABLE IF NOT EXISTS chapter_read_alias (manga_id INTEGER NOT NULL, reference_id TEXT NOT NULL, source TEXT NOT NULL, branch_key TEXT NOT NULL, url TEXT NOT NULL, PRIMARY KEY(manga_id,reference_id,source,branch_key,url))""",
            """CREATE TABLE IF NOT EXISTS chapter_read_clock (id INTEGER NOT NULL, actor TEXT NOT NULL, wall INTEGER NOT NULL, logical INTEGER NOT NULL, PRIMARY KEY(id))""",
            """CREATE TABLE IF NOT EXISTS chapter_read_count (manga_id INTEGER NOT NULL, branch_key TEXT NOT NULL, read_count INTEGER NOT NULL, total_count INTEGER NOT NULL, list_revision TEXT NOT NULL, chapter_references TEXT NOT NULL DEFAULT '[]', revision_wall INTEGER NOT NULL DEFAULT 0, revision_logical INTEGER NOT NULL DEFAULT 0, revision_actor TEXT NOT NULL DEFAULT 'unknown', PRIMARY KEY(manga_id,branch_key))""",
        )
    }
}
