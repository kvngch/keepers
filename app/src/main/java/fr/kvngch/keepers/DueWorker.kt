package fr.kvngch.keepers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.kvngch.keepers.data.AppDb
import java.util.concurrent.TimeUnit

// Controle quotidien des echeances detectees dans les documents : notification locale
// pour toute echeance a moins de 7 jours, une seule fois par document.
class DueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return Result.success()
        val dao = AppDb.get(ctx).itemDao()
        val now = System.currentTimeMillis()
        dao.dueSoon(now, now + 7L * 86_400_000).forEach { item ->
            val open = PendingIntent.getActivity(
                ctx, item.id.toInt(),
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle("Échéance : ${item.title}")
                .setContentText("Le ${Formats.date(item.dueDate ?: 0)}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(item.id.toInt(), notif) }
            dao.markDueNotified(item.id)
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL = "echeances"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "due-check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DueWorker>(1, TimeUnit.DAYS).build()
            )
        }
    }
}
