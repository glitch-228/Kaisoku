package org.koitharu.kotatsu.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
	tableName = "lnreader_sources",
)
data class LnReaderSourceEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "plugin_id")
	val pluginId: String,
	@ColumnInfo(name = "name") val name: String,
	@ColumnInfo(name = "lang") val lang: String? = null,
	@ColumnInfo(name = "site") val site: String? = null,
	@ColumnInfo(name = "version") val version: String = "1.0.0",
	@ColumnInfo(name = "icon_url") val iconUrl: String? = null,
	@ColumnInfo(name = "js_code") val jsCode: String,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)
