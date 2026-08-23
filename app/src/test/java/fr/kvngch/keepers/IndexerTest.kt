package fr.kvngch.keepers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexerTest {

    @Test
    fun detecteLesDatesNumeriques() {
        val dates = Indexer.detectDates("Facture du 12/09/2026, payable avant le 15.10.26")
        assertEquals(2, dates.size)
    }

    @Test
    fun detecteLesDatesTextuelles() {
        val dates = Indexer.detectDates("Garantie valable jusqu'au 3 août 2027 inclus")
        assertEquals(1, dates.size)
    }

    @Test
    fun ignoreLesDatesInvalides() {
        assertTrue(Indexer.detectDates("le 45/13/2026 n'existe pas, ni le 00/00/00").isEmpty())
    }

    @Test
    fun echeanceEstLaDateFutureLaPlusProche() {
        val a = Indexer.analyze(
            "Contrat signé le 01/01/2020, à renouveler le 01/06/2099 ou au plus tard le 01/06/2098."
        )
        assertNotNull(a.dueDate)
        assertTrue(Formats.date(a.dueDate!!).endsWith("2098"))
    }

    @Test
    fun extraitMontantsEtIban() {
        val a = Indexer.analyze(
            "Total 1 234,56 € à régler par virement sur FR76 3000 6000 0112 3456 7890 189 avant fin de mois."
        )
        assertTrue(a.extracted.contains("€"))
        assertTrue(a.extracted.contains("FR76"))
    }

    @Test
    fun texteSansDonneesStructureesResteVide() {
        val a = Indexer.analyze("Simple pense-bete sans montant ni date.")
        assertEquals("", a.extracted)
        assertEquals(null, a.dueDate)
    }

    @Test
    fun resumeCourtRenduTelQuel() {
        val a = Indexer.analyze("Une seule phrase courte.")
        assertEquals("Une seule phrase courte.", a.summary)
    }

    @Test
    fun resumeLongSelectionneDeuxPhrases() {
        val texte = (1..8).joinToString(" ") {
            "La facture numero $it concerne le contrat principal de maintenance annuelle."
        }
        val a = Indexer.analyze(texte)
        assertTrue(a.summary.isNotBlank())
        assertTrue(a.summary.length <= 200)
    }
}
