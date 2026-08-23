package fr.kvngch.keepers

import android.content.Context
import android.content.SharedPreferences
import fr.kvngch.keepers.data.DbKey

object Prefs {

    private fun p(context: Context): SharedPreferences =
        context.getSharedPreferences("keepers_prefs", Context.MODE_PRIVATE)

    fun lockGraceSeconds(c: Context): Int = p(c).getInt("lock_grace", 60)
    fun setLockGraceSeconds(c: Context, v: Int) = p(c).edit().putInt("lock_grace", v).apply()

    fun trashDays(c: Context): Int = p(c).getInt("trash_days", 30)
    fun setTrashDays(c: Context, v: Int) = p(c).edit().putInt("trash_days", v).apply()

    fun pdfMaxPages(c: Context): Int = p(c).getInt("pdf_pages", 10)
    fun setPdfMaxPages(c: Context, v: Int) = p(c).edit().putInt("pdf_pages", v).apply()

    fun useScanner(c: Context): Boolean = p(c).getBoolean("use_scanner", true)
    fun setUseScanner(c: Context, v: Boolean) = p(c).edit().putBoolean("use_scanner", v).apply()

    fun autoBackup(c: Context): Boolean = p(c).getBoolean("auto_backup", false)
    fun setAutoBackup(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_backup", v).apply()

    fun backupTree(c: Context): String? = p(c).getString("backup_tree", null)
    fun setBackupTree(c: Context, v: String) = p(c).edit().putString("backup_tree", v).apply()

    // Mot de passe des sauvegardes automatiques, chiffre par la cle Keystore
    fun backupPassword(c: Context): String? = p(c).getString("backup_pw", null)
        ?.let { runCatching { DbKey.reveal(c, it) }.getOrNull() }

    fun rawBackupPassword(c: Context): String? = p(c).getString("backup_pw", null)

    fun setBackupPassword(c: Context, v: String) =
        p(c).edit().putString("backup_pw", DbKey.protect(c, v)).apply()

    // Jours d'avance pour la notification d'echeance
    fun dueLeadDays(c: Context): Int = p(c).getInt("due_lead", 7)
    fun setDueLeadDays(c: Context, v: Int) = p(c).edit().putInt("due_lead", v).apply()

    // Couleurs dynamiques Material You (prend effet au prochain demarrage)
    fun dynamicColor(c: Context): Boolean = p(c).getBoolean("dynamic_color", false)
    fun setDynamicColor(c: Context, v: Boolean) = p(c).edit().putBoolean("dynamic_color", v).apply()

    // Cle Keystore liee a l'authentification de l'appareil (24 h de validite)
    fun strongKey(c: Context): Boolean = p(c).getBoolean("strong_key", false)
    fun setStrongKey(c: Context, v: Boolean) = p(c).edit().putBoolean("strong_key", v).apply()

    // Multi-coffres : identifiants de coffres et coffre courant
    fun vault(c: Context): String = p(c).getString("vault", "perso") ?: "perso"
    fun setVault(c: Context, v: String) = p(c).edit().putString("vault", v).apply()

    fun vaults(c: Context): List<String> =
        (p(c).getString("vaults", "perso") ?: "perso").split(',').filter { it.isNotBlank() }

    fun addVault(c: Context, v: String) {
        val all = (vaults(c) + v).distinct()
        p(c).edit().putString("vaults", all.joinToString(",")).apply()
    }
}
