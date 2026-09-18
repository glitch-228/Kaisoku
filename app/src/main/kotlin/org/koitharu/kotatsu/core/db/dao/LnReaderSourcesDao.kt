package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.LnReaderSourceEntity

@Dao
abstract class LnReaderSourcesDao {

	@Query("SELECT * FROM lnreader_sources ORDER BY name")
	abstract fun observeAll(): Flow<List<LnReaderSourceEntity>>

	@Query("SELECT * FROM lnreader_sources ORDER BY name")
	abstract suspend fun findAll(): List<LnReaderSourceEntity>

	@Query("SELECT * FROM lnreader_sources WHERE plugin_id = :pluginId")
	abstract suspend fun findById(pluginId: String): LnReaderSourceEntity?

	@Query("SELECT COUNT(*) FROM lnreader_sources WHERE plugin_id = :pluginId")
	abstract suspend fun countById(pluginId: String): Int

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun upsert(entity: LnReaderSourceEntity): Long

	@Query("DELETE FROM lnreader_sources WHERE plugin_id = :pluginId")
	abstract suspend fun delete(pluginId: String): Int

	@Query("DELETE FROM lnreader_sources")
	abstract suspend fun deleteAll()

	@Transaction
	open suspend fun upsertAll(entities: Collection<LnReaderSourceEntity>) {
		for (entity in entities) {
			upsert(entity)
		}
	}
}
