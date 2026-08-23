package fr.kvngch.keepers

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifierTest {

    @Test
    fun factureReconnue() {
        val cat = Indexer.classify(
            "Facture EDF mars", "Montant TTC 34,50 dont TVA 5,75, net à payer avant le 15", false
        )
        assertEquals(Category.FACTURE, cat)
    }

    @Test
    fun ordonnanceClasseeSante() {
        val cat = Indexer.classify(
            "Scan", "Ordonnance du docteur Martin, à présenter à la pharmacie", false
        )
        assertEquals(Category.SANTE, cat)
    }

    @Test
    fun bailClasseContrat() {
        val cat = Indexer.classify(
            "Bail appartement", "Le présent contrat de bail est conclu entre les parties", false
        )
        assertEquals(Category.CONTRAT, cat)
    }

    @Test
    fun noteResteNote() {
        assertEquals(Category.NOTE, Indexer.classify("Idée", "facture tva ttc", true))
    }

    @Test
    fun texteNeutreClasseAutre() {
        assertEquals(
            Category.AUTRE,
            Indexer.classify("Photo", "paysage de montagne au coucher du soleil", false)
        )
    }
}
