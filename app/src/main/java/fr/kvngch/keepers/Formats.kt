package fr.kvngch.keepers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formats {

    fun date(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(millis))

    fun dateTime(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(millis))

    fun month(millis: Long): String =
        SimpleDateFormat("MMMM yyyy", Locale.FRANCE).format(Date(millis))
            .replaceFirstChar { it.uppercase(Locale.FRANCE) }

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.FRANCE, "%.0f Ko", bytes / 1_000.0)
        else -> "$bytes o"
    }
}
