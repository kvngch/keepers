package fr.kvngch.keepers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.kvngch.keepers.data.AppDb
import fr.kvngch.keepers.data.ItemEntity
import fr.kvngch.keepers.data.ItemFts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportRestoreTest {

    @Test
    fun allerRetourExportRestaurationChiffres() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDb.get(context)
        val dao = db.itemDao()
        val docsDir = File(context.filesDir, "docs").apply { mkdirs() }
        runBlocking {
            db.clearAllTables()
            val item = ItemEntity(
                title = "Note de test",
                format = ".txt",
                sizeBytes = 5,
                addedAt = 123L,
                summary = "resume",
                content = "contenu cherchable",
                indexed = true,
                filePath = null,
                sha256 = "sha-test"
            )
            val id = dao.insert(item)
            dao.upsertFts(ItemFts(id, item.title, item.summary, item.content))

            val out = ByteArrayOutputStream()
            Vault.export(context, dao, out, "motdepasse".toCharArray())

            db.clearAllTables()
            assertEquals(0, dao.allOnce().size)

            val n = Vault.restore(
                context, dao, ByteArrayInputStream(out.toByteArray()),
                "motdepasse".toCharArray(), docsDir
            )
            assertEquals(1, n)
            val restored = dao.allOnce()
            assertEquals("Note de test", restored[0].title)
            assertEquals("contenu cherchable", restored[0].content)

            // le meme import une seconde fois est dedoublonne par sha256
            val again = Vault.restore(
                context, dao, ByteArrayInputStream(out.toByteArray()),
                "motdepasse".toCharArray(), docsDir
            )
            assertEquals(0, again)

            // un mauvais mot de passe doit echouer, pas restaurer du bruit
            try {
                Vault.restore(
                    context, dao, ByteArrayInputStream(out.toByteArray()),
                    "mauvais-mdp".toCharArray(), docsDir
                )
                fail("la restauration aurait dû échouer")
            } catch (expected: Exception) {
                // attendu : flux illisible avec une mauvaise cle
            }
            db.clearAllTables()
        }
    }
}
