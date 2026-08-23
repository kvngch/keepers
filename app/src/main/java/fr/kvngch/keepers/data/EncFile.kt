package fr.kvngch.keepers.data

import android.content.Context
import java.io.File
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Chiffrement des fichiers stockes : AES/GCM avec la meme passphrase que la base
// (elle-meme protegee par le Keystore), IV aleatoire de 12 octets en tete de fichier.
object EncFile {

    private fun key(context: Context): SecretKeySpec =
        SecretKeySpec(DbKey.passphrase(context, fr.kvngch.keepers.Prefs.vault(context)), "AES")

    fun encryptInPlace(context: Context, file: File) {
        val tmp = File(file.path + ".enc")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(context))
        tmp.outputStream().use { out ->
            out.write(cipher.iv)
            CipherOutputStream(out, cipher).use { cos ->
                file.inputStream().use { it.copyTo(cos) }
            }
        }
        check(file.delete()) { "suppression du fichier en clair impossible" }
        check(tmp.renameTo(file)) { "bascule vers le fichier chiffre impossible" }
    }

    fun decryptStream(context: Context, file: File): InputStream {
        val ins = file.inputStream()
        val iv = ByteArray(12)
        check(ins.read(iv) == 12) { "fichier chiffre invalide" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(context), GCMParameterSpec(128, iv))
        return CipherInputStream(ins, cipher)
    }

    fun decryptToCache(context: Context, file: File): File {
        val tmp = File.createTempFile("keepers", ".tmp", context.cacheDir)
        decryptStream(context, file).use { ins ->
            tmp.outputStream().use { ins.copyTo(it) }
        }
        return tmp
    }

    fun decryptBytes(context: Context, file: File): ByteArray =
        decryptStream(context, file).use { it.readBytes() }
}
