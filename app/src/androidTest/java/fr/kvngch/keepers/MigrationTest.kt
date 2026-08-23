package fr.kvngch.keepers

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kvngch.keepers.data.AppDb
import fr.kvngch.keepers.data.MIGRATION_1_2
import fr.kvngch.keepers.data.MIGRATION_2_3
import fr.kvngch.keepers.data.MIGRATION_3_4
import fr.kvngch.keepers.data.MIGRATION_4_5
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Reproduit le chemin critique de la 2.0.0 : une base creee par la version 1 du schema
// doit ressortir intacte et cherchable apres MIGRATION_1_2.
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun migrationDepuisV1PreserveLesDonnees() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test.db"
        val dbFile = context.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(name)
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        System.loadLibrary("sqlcipher")
        val passphrase = "passphrase-de-test".toByteArray()

        // Base v1 brute, avec le schema exact genere par Room version 1
        val v1 = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, passphrase, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null, null
        )
        v1.execSQL(
            "CREATE TABLE IF NOT EXISTS `items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `format` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "`summary` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                "`indexed` INTEGER NOT NULL, `filePath` TEXT)"
        )
        v1.execSQL(
            "INSERT INTO items(title, format, sizeBytes, addedAt, summary, content, indexed, filePath) " +
                "VALUES('Facture EDF', '.pdf', 100, 1723000000000, 'resume', 'contenu edf', 1, NULL)"
        )
        v1.version = 1
        v1.close()

        val db = Room.databaseBuilder(context, AppDb::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        try {
            runBlocking {
                val items = db.itemDao().allOnce()
                assertEquals(1, items.size)
                assertEquals("Facture EDF", items[0].title)
                assertEquals("", items[0].sha256)
                assertEquals(false, items[0].fileEnc)
                // la table FTS creee par la migration doit etre peuplee
                assertEquals(1, db.itemDao().searchFts("edf*").first().size)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
