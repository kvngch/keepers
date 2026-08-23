package fr.kvngch.keepers.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import fr.kvngch.keepers.Prefs
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase

// Cle de chiffrement des bases : une passphrase aleatoire de 32 octets par coffre,
// stockee dans les SharedPreferences chiffree par une cle AES/GCM non exportable du
// Keystore Android. En mode renforce, la cle Keystore exige une authentification de
// l'appareil datant de moins de 24 h.
object DbKey {

    private const val ALIAS = "keepers-db"
    private const val ALIAS_AUTH = "keepers-db-auth"
    private const val PREFS = "keepers_sec"

    private val cache = HashMap<String, ByteArray>()

    private fun entryName(vault: String): String =
        if (vault == "perso") "db_key" else "db_key_$vault"

    @Synchronized
    fun passphrase(context: Context, vault: String): ByteArray {
        cache[vault]?.let { return it }
        return computePassphrase(context, vault).also { cache[vault] = it }
    }

    private fun computePassphrase(context: Context, vault: String): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entry = entryName(vault)
        prefs.getString(entry, null)?.let { return decrypt(context, it) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Base creee en clair par une version < 1.3.0 : la chiffrer avant le premier acces.
        // La cle n'est enregistree qu'apres migration reussie, un echec fait reessayer
        // au prochain lancement au lieu de perdre la base.
        val dbFile = context.getDatabasePath(AppDb.dbName(vault))
        if (dbFile.exists()) migratePlain(dbFile, raw)
        prefs.edit().putString(entry, encrypt(context, raw)).apply()
        return raw
    }

    // Bascule du mode de protection de la cle Keystore : re-chiffre toutes les
    // passphrases avec une nouvelle cle. A appeler juste apres une authentification.
    @Synchronized
    fun setAuthRequired(context: Context, enabled: Boolean) {
        if (Prefs.strongKey(context) == enabled) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.all
            .filterKeys { it.startsWith("db_key") }
            .mapValues { decrypt(context, it.value as String) }
        val backupPw = Prefs.rawBackupPassword(context)?.let { reveal(context, it) }
        Prefs.setStrongKey(context, enabled)
        val editor = prefs.edit()
        entries.forEach { (k, v) -> editor.putString(k, encrypt(context, v)) }
        editor.apply()
        backupPw?.let { Prefs.setBackupPassword(context, it) }
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
    fun protect(context: Context, value: String): String = encrypt(context, value.toByteArray())

    fun reveal(context: Context, stored: String): String = String(decrypt(context, stored))

    private fun keystoreKey(context: Context): SecretKey {
        val strong = Prefs.strongKey(context)
        val alias = if (strong) ALIAS_AUTH else ALIAS
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (strong) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(
                    86_400,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(86_400)
            }
        }
        gen.init(builder.build())
        return gen.generateKey()
    }

    private fun encrypt(context: Context, raw: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(context))
        val ct = cipher.doFinal(raw)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, stored: String): ByteArray {
        val parts = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(context), GCMParameterSpec(128, parts[0]))
        return cipher.doFinal(parts[1])
    }
}
