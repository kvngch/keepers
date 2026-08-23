package fr.kvngch.keepers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.kvngch.keepers.data.AppDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Sauvegarde automatique hebdomadaire : export chiffre ecrit dans le dossier choisi
// par l'utilisateur (SAF), rotation sur les 4 dernieres sauvegardes.
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs.autoBackup(ctx)) return Result.success()
        val tree = Prefs.backupTree(ctx)?.let(Uri::parse) ?: return Result.success()
        val password = Prefs.backupPassword(ctx) ?: return Result.success()
        val resolver = ctx.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.FRANCE).format(Date())
        return runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val docUri = DocumentsContract.createDocument(
                resolver, parent, "application/octet-stream", "keepers-auto-$stamp.keepers"
            ) ?: error("création du fichier impossible")
            resolver.openOutputStream(docUri)?.use { out ->
                Vault.export(ctx, AppDb.get(ctx).itemDao(), out, password.toCharArray())
            } ?: error("écriture impossible")
            rotate(tree)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun rotate(tree: Uri) {
        val resolver = applicationContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val backups = mutableListOf<Pair<String, Uri>>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith("keepers-auto-")) {
                    backups += name to DocumentsContract.buildDocumentUriUsingTree(
                        tree, c.getString(0)
                    )
                }
            }
        }
        backups.sortedByDescending { it.first }.drop(4).forEach {
            runCatching { DocumentsContract.deleteDocument(resolver, it.second) }
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "auto-backup",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("auto-backup")
        }
    }
}
