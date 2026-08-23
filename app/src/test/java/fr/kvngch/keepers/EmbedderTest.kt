package fr.kvngch.keepers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedderTest {

    @Test
    fun serialisationAllerRetour() {
        val vec = floatArrayOf(0.1f, -2.5f, 3.14159f, 0f)
        assertArrayEquals(vec, Embedder.fromBytes(Embedder.toBytes(vec)), 0f)
    }

    @Test
    fun cosineDeVecteursIdentiquesVautUn() {
        val vec = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, Embedder.cosine(vec, vec), 1e-5f)
    }

    @Test
    fun cosineDeVecteursOrthogonauxVautZero() {
        assertEquals(0f, Embedder.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
    }

    @Test
    fun cosineGereLesTaillesIncompatiblesEtLeVecteurNul() {
        assertEquals(0f, Embedder.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0f)
        assertEquals(0f, Embedder.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 0f)
        assertTrue(Embedder.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)) < 0f)
    }
}
