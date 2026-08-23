package fr.kvngch.keepers.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

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
    val filePath: String?
)

@Dao
interface ItemDao {

    @Query("SELECT * FROM items ORDER BY addedAt DESC")
    fun all(): Flow<List<ItemEntity>>

    @Query(
        "SELECT * FROM items WHERE title LIKE '%' || :q || '%' " +
            "OR summary LIKE '%' || :q || '%' " +
            "OR content LIKE '%' || :q || '%' ORDER BY addedAt DESC"
    )
    fun search(q: String): Flow<List<ItemEntity>>

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Delete
    suspend fun delete(item: ItemEntity)
}

@Database(entities = [ItemEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "keepers.db"
                ).build().also { instance = it }
            }
    }
}
