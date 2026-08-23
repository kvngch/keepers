package fr.kvngch.keepers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.kvngch.keepers.data.AppDb

// Indexation hors du cycle de vie de l'ecran : survit a la mort du process
// pendant l'OCR d'un gros document.
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", -1)
        if (id < 0) return Result.failure()
        val dao = AppDb.get(applicationContext).itemDao()
        val item = dao.byId(id) ?: return Result.success()
        if (item.indexed) return Result.success()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            Indexer.index(
                applicationContext, dao, item, recognizer,
                maxPdfPages = Prefs.pdfMaxPages(applicationContext)
            )
        } catch (e: Exception) {
            // l'element reste visible dans la file avec son erreur, relancable a la main
            dao.setStatus(id, "Erreur : indexation interrompue")
        } finally {
            recognizer.close()
        }
        return Result.success()
    }

    companion object {
        const val TAG = "index"

        fun enqueue(context: Context, id: Long) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<IndexWorker>()
                    .setInputData(workDataOf("id" to id))
                    .addTag(TAG)
                    .build()
            )
        }
    }
}
