package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration30To31 : Migration(30, 31) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `lnreader_sources` (
				`plugin_id` TEXT NOT NULL,
				`name` TEXT NOT NULL,
				`lang` TEXT,
				`site` TEXT,
				`version` TEXT NOT NULL,
				`icon_url` TEXT,
				`js_code` TEXT NOT NULL,
				`created_at` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				PRIMARY KEY(`plugin_id`)
			)
			""".trimIndent(),
		)
	}
}
