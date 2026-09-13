package com.kline.pilot.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "deliveries", indices = [Index(value = ["owner", "branch"])])
data class Delivery(@PrimaryKey val id: String, val owner: String, val branch: String,
    val title: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "photos", indices = [Index(value = ["owner", "branch"]), Index("deliveryId")])
data class PendingPhoto(@PrimaryKey val id: String, val owner: String, val branch: String,
    val deliveryId: String, val categoryId: String, val categoryPath: String,
    val originalPath: String, val uploadPath: String, val sha256: String,
    val originalBytes: Long, val uploadBytes: Long, val state: String = "review",
    val message: String = "Saved on this phone. Review before uploading.", val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(), @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0)

@Entity(tableName = "reference_cache", primaryKeys = ["owner", "branch"])
data class ReferenceCache(val owner: String, val branch: String, val json: String)

@Dao
interface PilotDao {
    @Insert suspend fun insertDelivery(delivery: Delivery)
    @Insert suspend fun insertPhoto(photo: PendingPhoto)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cacheReference(cache: ReferenceCache)
    @Query("SELECT * FROM reference_cache WHERE owner=:owner AND branch=:branch")
    suspend fun reference(owner: String, branch: String): ReferenceCache?
    @Query("SELECT * FROM deliveries WHERE owner=:owner AND branch=:branch ORDER BY createdAt DESC")
    fun deliveries(owner: String, branch: String): Flow<List<Delivery>>
    @Query("SELECT * FROM photos WHERE owner=:owner AND branch=:branch ORDER BY createdAt DESC")
    fun photos(owner: String, branch: String): Flow<List<PendingPhoto>>
    @Query("SELECT * FROM photos WHERE id=:id") suspend fun photo(id: String): PendingPhoto?
    @Query("SELECT * FROM deliveries WHERE id=:id") suspend fun delivery(id: String): Delivery?
    @Query("SELECT * FROM photos WHERE owner=:owner AND state IN ('queued','uploading','checking','linking','retry','auth')")
    suspend fun resumable(owner: String): List<PendingPhoto>
    @Query("UPDATE photos SET state=:state,message=:message WHERE id=:id")
    suspend fun updateState(id: String, state: String, message: String)
    @Query("UPDATE photos SET attempts=attempts+1 WHERE id=:id") suspend fun incrementAttempts(id: String)
    @Query("UPDATE photos SET nextAttemptAt=:timestamp WHERE id=:id") suspend fun deferUntil(id: String, timestamp: Long)
}

@Database(entities = [Delivery::class, PendingPhoto::class, ReferenceCache::class], version = 2, exportSchema = true)
abstract class PilotDatabase : RoomDatabase() { abstract fun pilotDao(): PilotDao }

val PILOT_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE photos ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0") }
}
