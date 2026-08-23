package fr.kvngch.keepers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

// Widget d'ecran d'accueil : un bouton qui ouvre directement le scanner de documents
class CaptureWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "fr.kvngch.keepers.WIDGET_SCAN"
                putExtra("keepers_action", "scan")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val views = RemoteViews(context.packageName, R.layout.widget_capture)
            views.setOnClickPendingIntent(
                R.id.widget_button,
                PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            manager.updateAppWidget(id, views)
        }
    }
}
