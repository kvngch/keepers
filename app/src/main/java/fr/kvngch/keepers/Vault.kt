package fr.kvngch.keepers

import android.content.Context
import android.util.Base64
import fr.kvngch.keepers.data.EncFile
import fr.kvngch.keepers.data.ItemDao
import fr.kvngch.keepers.data.ItemEntity
import fr.kvngch.keepers.data.ItemFts
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

// Sauvegarde portable du coffre : zip (manifest.json + fichiers dechiffres) entierement
// chiffre AES/GCM avec une cle derivee du mot de passe utilisateur (PBKDF2, 150k iterations).
// En-tete : "KPRS1" + sel (16) + IV (12).
object Vault {

    private const val MAGIC = "KPRS1"

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 150_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    suspend fun export(context: Context, dao: ItemDao, out: OutputStream, password: CharArray) {
        val items = dao.allOnce()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt))
        out.write(MAGIC.toByteArray())
        out.write(salt)
        out.write(cipher.iv)
        ZipOutputStream(CipherOutputStream(out, cipher)).use { zip ->
            val manifest = JSONArray()
            for (i in items) {
                manifest.put(JSONObject().apply {
                    put("id", i.id)
                    put("title", i.title)
                    put("format", i.format)
                    put("sizeBytes", i.sizeBytes)
                    put("addedAt", i.addedAt)
                    put("summary", i.summary)
                    put("content", i.content)
                    put("sha256", i.sha256)
                    put("extracted", i.extracted)
                    put("category", i.category)
                    put("tags", i.tags)
                    put("catManual", i.catManual)
                    i.docDate?.let { put("docDate", it) }
                    i.dueDate?.let { put("dueDate", it) }
                    i.thumb?.let { put("thumb", Base64.encodeToString(it, Base64.NO_WRAP)) }
                    put("hasFile", i.filePath != null)
                })
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            for (i in items) {
                val path = i.filePath ?: continue
                zip.putNextEntry(ZipEntry("files/${i.id}"))
                EncFile.decryptStream(context, File(path)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    suspend fun restore(
        context: Context,
        dao: ItemDao,
        ins: InputStream,
        password: CharArray,
        docsDir: File
    ): Int {
        val magic = ByteArray(5)
        require(ins.read(magic) == 5 && String(magic) == MAGIC) { "format de sauvegarde inconnu" }
        val salt = ByteArray(16)
        require(ins.read(salt) == 16) { "en-tete tronque" }
        val iv = ByteArray(12)
        require(ins.read(iv) == 12) { "en-tete tronque" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        var imported = 0
        ZipInputStream(CipherInputStream(ins, cipher)).use { zip ->
            val first = zip.nextEntry ?: throw IllegalArgumentException("archive vide")
            require(first.name == "manifest.json") { "manifest manquant" }
            val manifest = JSONArray(String(zip.readBytes()))
            val newIds = HashMap<Long, Long>()
            for (k in 0 until manifest.length()) {
                val o = manifest.getJSONObject(k)
                val sha = o.optString("sha256")
                if (sha.isNotBlank() && dao.bySha(sha) != null) continue
                val item = ItemEntity(
                    title = o.getString("title"),
                    format = o.getString("format"),
                    sizeBytes = o.getLong("sizeBytes"),
                    addedAt = o.getLong("addedAt"),
                    summary = o.getString("summary"),
                    content = o.getString("content"),
                    indexed = true,
                    filePath = null,
                    sha256 = sha,
                    extracted = o.optString("extracted"),
                    category = o.optString("category"),
                    tags = o.optString("tags"),
                    catManual = o.optBoolean("catManual"),
                    docDate = if (o.has("docDate")) o.getLong("docDate") else null,
                    dueDate = if (o.has("dueDate")) o.getLong("dueDate") else null,
                    fileEnc = true,
                    thumb = if (o.has("thumb"))
                        Base64.decode(o.getString("thumb"), Base64.NO_WRAP) else null
                )
                val id = dao.insert(item)
                dao.upsertFts(Indexer.ftsRow(item.copy(id = id)))
                if (o.optBoolean("hasFile")) newIds[o.getLong("id")] = id
                imported++
            }
            while (true) {
                val entry = zip.nextEntry ?: break
                val oldId = entry.name.removePrefix("files/").toLongOrNull() ?: continue
                val newId = newIds[oldId] ?: continue
                val dest = File(docsDir, "restore-${System.currentTimeMillis()}-$newId")
                dest.outputStream().use { zip.copyTo(it) }
                EncFile.encryptInPlace(context, dest)
                dao.byId(newId)?.let { dao.update(it.copy(filePath = dest.absolutePath)) }
            }
        }
        return imported
    }
}
