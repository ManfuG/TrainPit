package io.github.manfug.trainpit.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** Only opaque routing identifiers and outbox status are indexed; all domain content is encrypted. */
@Entity(tableName = "revisions", indices = [Index(value = ["ownerId", "subjectId", "kind"]), Index(value = ["rootId"]), Index(value = ["pending"])])
internal data class RevisionRow(
    @PrimaryKey val id: String,
    val rootId: String,
    val ownerId: String,
    val subjectId: String,
    val kind: String,
    val pending: Boolean,
    val ciphertext: ByteArray,
)

@Entity(tableName = "metadata")
internal data class MetadataRow(@PrimaryKey val key: String, val ciphertext: ByteArray)

@Dao
internal interface LocalDao {
    @Query("SELECT * FROM revisions ORDER BY rowid ASC") suspend fun revisions(): List<RevisionRow>
    @Query("SELECT id FROM revisions WHERE pending = 1 AND ownerId = :ownerId AND (:afterId IS NULL OR id > :afterId) ORDER BY id ASC LIMIT :limit")
    suspend fun pendingIds(ownerId: String, limit: Int, afterId: String?): List<String>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(rows: List<RevisionRow>)
    @Query("UPDATE revisions SET pending = 0 WHERE id IN (:ids)") suspend fun markUploaded(ids: List<String>)
    @Query("DELETE FROM revisions WHERE id IN (:ids)") suspend fun remove(ids: List<String>)
    @Query("DELETE FROM revisions") suspend fun clearRevisions()
    @Query("SELECT * FROM metadata") suspend fun metadata(): List<MetadataRow>
    @Query("SELECT * FROM metadata WHERE `key` = :key") suspend fun metadata(key: String): MetadataRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: MetadataRow)
    @Query("DELETE FROM metadata WHERE `key` LIKE 'cursor:%'") suspend fun clearCursors()
    @Query("DELETE FROM metadata") suspend fun clearMetadata()
}

/** Initial released schema. Future changes require an explicit data-preserving Room migration. */
@Database(entities = [RevisionRow::class, MetadataRow::class], version = 1, exportSchema = true)
internal abstract class LocalDatabase : RoomDatabase() {
    abstract fun local(): LocalDao
}
