package fr.kvngch.keepers

import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {

    @Test
    fun taillesLisiblesEnFrancais() {
        assertEquals("512 o", Formats.size(512))
        assertEquals("2 Ko", Formats.size(2_048))
        assertEquals("1,2 Mo", Formats.size(1_200_000))
    }

    @Test
    fun dateAuFormatFrancais() {
        val millis = GregorianCalendar(2026, 1, 1).timeInMillis
        assertEquals("01/02/2026", Formats.date(millis))
    }
}
