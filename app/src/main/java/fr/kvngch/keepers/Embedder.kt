package fr.kvngch.keepers

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// Embeddings semantiques 100 % locaux : Universal Sentence Encoder (MediaPipe, tflite
// embarque dans l'APK). Si le modele ne charge pas, la recherche retombe sur le FTS seul.
object Embedder {

    @Volatile
    private var instance: TextEmbedder? = null
    private var failed = false
    private val lock = Any()

    private fun get(context: Context): TextEmbedder? {
        instance?.let { return it }
        if (failed) return null
        synchronized(lock) {
            instance?.let { return it }
            if (failed) return null
            val created = runCatching {
                TextEmbedder.createFromOptions(
                    context.applicationContext,
                    TextEmbedder.TextEmbedderOptions.builder()
                        .setBaseOptions(
                            BaseOptions.builder()
                                .setModelAssetPath("universal_sentence_encoder.tflite")
                                .build()
                        )
                        .build()
                )
            }.getOrNull()
            if (created == null) failed = true else instance = created
            return created
        }
    }

    fun embed(context: Context, text: String): FloatArray? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val embedder = get(context) ?: return null
        return runCatching {
            synchronized(lock) {
                embedder.embed(clean.take(1_000))
                    .embeddingResult().embeddings()[0].floatEmbedding()
            }
        }.getOrNull()
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }

    fun toBytes(vec: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vec.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat(it * 4) }
    }
}
