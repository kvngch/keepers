package fr.kvngch.keepers.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase

// Cle de chiffrement de la base : passphrase aleatoire de 32 octets, stockee dans les
// SharedPreferences chiffree par une cle AES/GCM non exportable du Keystore Android.
object DbKey {

    private const val ALIAS = "keepers-db"
    private const val PREFS = "keepers_sec"
    private const val ENTRY = "db_key"

    @Volatile
    private var cached: ByteArray? = null

    @Synchronized
    fun passphrase(context: Context, dbFile: File): ByteArray {
        cached?.let { return it }
        return computePassphrase(context, dbFile).also { cached = it }
    }

    private fun computePassphrase(context: Context, dbFile: File): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(ENTRY, null)?.let { return decrypt(it) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Base creee en clair par une version < 1.3.0 : la chiffrer avant le premier acces.
        // La cle n'est enregistree qu'apres migration reussie, un echec fait reessayer
        // au prochain lancement au lieu de perdre la base.
        if (dbFile.exists()) migratePlain(dbFile, raw)
        prefs.edit().putString(ENTRY, encrypt(raw)).apply()
        return raw
    }

    private fun migratePlain(dbFile: File, passphrase: ByteArray) {
        val tmp = File(dbFile.parentFile, dbFile.name + ".enc")
        tmp.delete()
        val hex = passphrase.joinToString("") { "%02x".format(it) }
        val plain = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, null, null
        )
        val version = plain.version
        plain.rawExecSQL("ATTACH DATABASE '${tmp.absolutePath}' AS encrypted KEY \"x'$hex'\"")
        plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
        plain.rawExecSQL("DETACH DATABASE encrypted")
        plain.close()
        val enc = SQLiteDatabase.openDatabase(
            tmp.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READWRITE, null, null
        )
        enc.version = version
        enc.close()
        check(dbFile.delete()) { "suppression de la base en clair impossible" }
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        check(tmp.renameTo(dbFile)) { "bascule vers la base chiffree impossible" }
    }

    // Protection generique d'une petite valeur (ex : mot de passe de sauvegarde auto)
    fun protect(value: String): String = encrypt(value.toByteArray())

    fun reveal(stored: String): String = String(decrypt(stored))

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(raw: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ct = cipher.doFinal(raw)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): ByteArray {
        val parts = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, parts[0]))
        return cipher.doFinal(parts[1])
    }
}
