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
    val thumb: ByteArray? = null,
    // etape de traitement en cours (vide = rien en cours), vecteur semantique
    @ColumnInfo(defaultValue = "") val status: String = "",
    val embedding: ByteArray? = null,
    // id de categorie detectee (Category.id), vide = pas encore classe
    @ColumnInfo(defaultValue = "") val category: String = "",
    // categorie fixee a la main : l'indexation ne l'ecrase plus
    @ColumnInfo(defaultValue = "0") val catManual: Boolean = false,
    // tags libres separes par des virgules
    @ColumnInfo(defaultValue = "") val tags: String = "",
    // date du document detectee dans le texte (differente de la date d'ajout)
    val docDate: Long? = null
) {
    fun refDate(): Long = docDate ?: addedAt

    fun tagList(): List<String> =
        tags.split(',').map { it.trim() }.filter { it.isNotBlank() }
}

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

    @Query("SELECT * FROM items WHERE indexed = 0 AND deletedAt IS NULL ORDER BY addedAt ASC")
    fun pending(): Flow<List<ItemEntity>>

    @Query("UPDATE items SET status = :s WHERE id = :id")
    suspend fun setStatus(id: Long, s: String)

    @Query("SELECT * FROM items WHERE indexed = 1 AND embedding IS NULL AND deletedAt IS NULL")
    suspend fun missingEmbedding(): List<ItemEntity>

    @Query("UPDATE items SET embedding = :vec WHERE id = :id")
    suspend fun updateEmbedding(id: Long, vec: ByteArray)

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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN status TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE items ADD COLUMN embedding BLOB")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN category TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN catManual INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE items ADD COLUMN docDate INTEGER")
    }
}

@Database(entities = [ItemEntity::class, ItemFts::class], version = 5, exportSchema = true)
abstract class AppDb : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        @Volatile
        private var instanceVault: String? = null

        // le coffre "perso" garde les noms historiques pour la retrocompatibilite
        fun dbName(vault: String): String =
            if (vault == "perso") "keepers.db" else "keepers-$vault.db"

        fun docsDirName(vault: String): String =
            if (vault == "perso") "docs" else "docs-$vault"

        fun get(context: Context): AppDb {
            val vault = fr.kvngch.keepers.Prefs.vault(context)
            instance?.let { if (instanceVault == vault) return it }
            synchronized(this) {
                instance?.let { if (instanceVault == vault) return it }
                instance?.close()
                System.loadLibrary("sqlcipher")
                val app = context.applicationContext
                val passphrase = DbKey.passphrase(app, vault)
                return Room.databaseBuilder(app, AppDb::class.java, dbName(vault))
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also {
                        instance = it
                        instanceVault = vault
                    }
            }
        }

        fun reset() {
            synchronized(this) {
                instance?.close()
                instance = null
                instanceVault = null
            }
        }
    }
}
