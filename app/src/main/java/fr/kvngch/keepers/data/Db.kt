package fr.kvngch.keepers.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val format: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val summary: String,
    val content: String,
    val indexed: Boolean,
    val filePath: String?,
    @ColumnInfo(defaultValue = "") val sha256: String = "",
    val deletedAt: Long? = null,
    val dueDate: Long? = null,
    @ColumnInfo(defaultValue = "") val extracted: String = "",
    @ColumnInfo(defaultValue = "0") val fileEnc: Boolean = false,
    @ColumnInfo(defaultValue = "0") val dueNotified: Boolean = false,
    val thumb: ByteArray? = null
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "items_fts")
data class ItemFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val title: String,
    val summary: String,
    val content: String
)

@Dao
interface ItemDao {

    @Query("SELECT * FROM items WHERE deletedAt IS NULL ORDER BY addedAt DESC")
    fun all(): Flow<List<ItemEntity>>

    @Query(
        "SELECT i.* FROM items i JOIN items_fts f ON i.id = f.rowid " +
            "WHERE items_fts MATCH :q AND i.deletedAt IS NULL ORDER BY i.addedAt DESC"
    )
    fun searchFts(q: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun trash(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE deletedAt IS NULL")
    suspend fun allOnce(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: Long): ItemEntity?

    @Query("SELECT * FROM items WHERE sha256 = :sha AND deletedAt IS NULL LIMIT 1")
    suspend fun bySha(sha: String): ItemEntity?

    @Query("SELECT * FROM items WHERE fileEnc = 0 AND filePath IS NOT NULL")
    suspend fun unencryptedFiles(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeable(before: Long): List<ItemEntity>

    @Query(
        "SELECT * FROM items WHERE dueDate BETWEEN :from AND :to " +
            "AND dueNotified = 0 AND deletedAt IS NULL"
    )
    suspend fun dueSoon(from: Long, to: Long): List<ItemEntity>

    @Query("UPDATE items SET dueNotified = 1 WHERE id = :id")
    suspend fun markDueNotified(id: Long)

    @Query("UPDATE items SET deletedAt = :t WHERE id = :id")
    suspend fun moveToTrash(id: Long, t: Long)

    @Query("UPDATE items SET deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    @Delete
    suspend fun delete(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(fts: ItemFts)

    @Query("DELETE FROM items_fts WHERE rowid = :id")
    suspend fun deleteFts(id: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE items ADD COLUMN deletedAt INTEGER")
        db.execSQL("ALTER TABLE items ADD COLUMN dueDate INTEGER")
        db.execSQL("ALTER TABLE items ADD COLUMN extracted TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE items ADD COLUMN fileEnc INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN dueNotified INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN thumb BLOB")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `items_fts` " +
                "USING FTS4(`title` TEXT, `summary` TEXT, `content` TEXT, tokenize=unicode61)"
        )
        db.execSQL(
            "INSERT INTO items_fts(rowid, title, summary, content) " +
                "SELECT id, title, summary, content FROM items"
        )
    }
}

@Database(entities = [ItemEntity::class, ItemFts::class], version = 2, exportSchema = true)
abstract class AppDb : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: run {
                    System.loadLibrary("sqlcipher")
                    val app = context.applicationContext
                    val passphrase = DbKey.passphrase(app, app.getDatabasePath("keepers.db"))
                    Room.databaseBuilder(app, AppDb::class.java, "keepers.db")
                        .openHelperFactory(SupportOpenHelperFactory(passphrase))
                        .addMigrations(MIGRATION_1_2)
                        .build().also { instance = it }
                }
            }
    }
}
