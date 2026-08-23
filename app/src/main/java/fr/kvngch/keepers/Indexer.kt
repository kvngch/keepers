package fr.kvngch.keepers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import fr.kvngch.keepers.data.EncFile
import fr.kvngch.keepers.data.ItemDao
import fr.kvngch.keepers.data.ItemEntity
import fr.kvngch.keepers.data.ItemFts
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.GregorianCalendar
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Toute l'analyse locale d'un document : OCR, PDF, resume extractif, dates et montants,
// miniature. Utilise par IndexWorker (fichiers) et par le ViewModel (notes).
object Indexer {

    private val IMAGE_EXT = setOf(".jpg", ".jpeg", ".png", ".webp", ".bmp")
    private val TEXT_EXT = setOf(".txt", ".md", ".csv", ".json", ".log")

    fun isImage(format: String): Boolean = format in IMAGE_EXT

    data class Analysis(val summary: String, val extracted: String, val dueDate: Long?)

    suspend fun index(
        context: Context,
        dao: ItemDao,
        item: ItemEntity,
        recognizer: TextRecognizer,
        maxPdfPages: Int = 10
    ) {
        val src = item.filePath?.let { File(it) } ?: return
        if (!src.exists()) return
        val plain = EncFile.decryptToCache(context, src)
        try {
            val content = when {
                item.format in TEXT_EXT ->
                    runCatching { plain.readText().take(100_000) }.getOrDefault("")
                item.format == ".pdf" -> pdfText(plain, recognizer, maxPdfPages)
                isImage(item.format) -> ocrFile(context, plain, recognizer)
                else -> ""
            }
            val a = analyze(content)
            val summary = when {
                content.isNotBlank() -> a.summary
                isImage(item.format) || item.format == ".pdf" ->
                    "Document stocké localement, aucun texte détecté par l'OCR."
                else -> "Fichier stocké localement. Métadonnées indexées, contenu non extrait."
            }
            val updated = item.copy(
                content = content,
                summary = summary,
                extracted = a.extracted,
                dueDate = a.dueDate,
                thumb = thumbnail(plain, item.format),
                indexed = true
            )
            dao.update(updated)
            dao.upsertFts(ItemFts(item.id, updated.title, updated.summary, updated.content))
        } finally {
            plain.delete()
        }
    }

    // Resume extractif local : les deux phrases les plus denses en mots frequents.
    // ponytail: heuristique simple; Gemini Nano (AICore) si un appareil compatible arrive.
    fun analyze(text: String): Analysis {
        if (text.isBlank()) return Analysis("", "", null)
        val now = System.currentTimeMillis()
        val dates = detectDates(text)
        val due = dates.filter { it > now }.minOrNull()
        val amounts = AMOUNT.findAll(text).map { it.value.trim() }.distinct().take(3).toList()
        val ibans = IBAN.findAll(text).map { it.value.replace(" ", "") }.distinct().take(1).toList()
        val dateStrs = dates.sorted().map { Formats.date(it) }.distinct().take(3)
        val extracted = (amounts + dateStrs + ibans).joinToString(" · ")
        return Analysis(summarize(text), extracted, due)
    }

    private fun summarize(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val sentences = clean.split(Regex("(?<=[.!?])\\s+")).filter { it.length > 20 }
        if (sentences.size < 3) return clean.take(160)
        val word = Regex("[\\p{L}]{4,}")
        val freq = word.findAll(clean.lowercase()).groupingBy { it.value }.eachCount()
        val scored = sentences.associateWith { s ->
            word.findAll(s.lowercase()).sumOf { freq[it.value] ?: 0 }.toDouble() / (s.length + 1)
        }
        val top = scored.entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet()
        return sentences.filter { it in top }.joinToString(" ").take(200)
    }

    private val NUM_DATE = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})\\b")
    private val TXT_DATE = Regex(
        "\\b(\\d{1,2})(?:er)?\\s+(janvier|février|mars|avril|mai|juin|juillet|août|" +
            "septembre|octobre|novembre|décembre)\\s+(\\d{4})\\b",
        RegexOption.IGNORE_CASE
    )
    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet",
        "août", "septembre", "octobre", "novembre", "décembre"
    )
    private val AMOUNT = Regex("\\d{1,3}(?:[ \\u00A0]\\d{3})*(?:[.,]\\d{2})?\\s?(?:€|EUR\\b)")
    private val IBAN = Regex("\\bFR\\d{2}(?:\\s?[A-Z0-9]{4}){5}\\s?[A-Z0-9]{3}\\b")

    fun detectDates(text: String): List<Long> {
        val out = mutableListOf<Long>()
        NUM_DATE.findAll(text).forEach { m ->
            val (d, mo, y) = m.destructured
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            toMillis(d.toInt(), mo.toInt(), year)?.let(out::add)
        }
        TXT_DATE.findAll(text).forEach { m ->
            val mo = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
            if (mo > 0) toMillis(m.groupValues[1].toInt(), mo, m.groupValues[3].toInt())?.let(out::add)
        }
        return out.distinct()
    }

    private fun toMillis(d: Int, m: Int, y: Int): Long? {
        if (d !in 1..31 || m !in 1..12 || y !in 1990..2100) return null
        return GregorianCalendar(y, m - 1, d).timeInMillis
    }

    private suspend fun ocrFile(context: Context, file: File, recognizer: TextRecognizer): String =
        suspendCancellableCoroutine { cont ->
            val input = runCatching {
                InputImage.fromFilePath(context, Uri.fromFile(file))
            }.getOrNull()
            if (input == null) {
                cont.resume("")
                return@suspendCancellableCoroutine
            }
            recognizer.process(input)
                .addOnSuccessListener { cont.resume(it.text.take(100_000)) }
                .addOnFailureListener { cont.resume("") }
        }

    private suspend fun ocrBitmap(bitmap: Bitmap, recognizer: TextRecognizer): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume("") }
        }

    private suspend fun pdfText(file: File, recognizer: TextRecognizer, maxPages: Int): String {
        val sb = StringBuilder()
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pages = minOf(renderer.pageCount, maxPages)
                    for (i in 0 until pages) {
                        val page = renderer.openPage(i)
                        try {
                            val scale = 2f
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt(),
                                (page.height * scale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            sb.append(ocrBitmap(bitmap, recognizer)).append('\n')
                            bitmap.recycle()
                        } finally {
                            page.close()
                        }
                    }
                }
            }
        }
        return sb.toString().trim().take(100_000)
    }

    fun thumbnail(plain: File, format: String): ByteArray? = runCatching {
        val bmp: Bitmap? = when {
            isImage(format) -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(plain.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512) sample *= 2
                BitmapFactory.decodeFile(
                    plain.path,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
            format == ".pdf" ->
                ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        if (r.pageCount == 0) null else {
                            val page = r.openPage(0)
                            try {
                                val w = 256
                                val h = (page.height.toFloat() / page.width * w).toInt()
                                    .coerceAtLeast(1)
                                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                b.eraseColor(Color.WHITE)
                                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            } finally {
                                page.close()
                            }
                        }
                    }
                }
            else -> null
        }
        bmp?.let {
            val out = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 70, out)
            it.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    // Confidentialite : retire la geolocalisation EXIF des JPEG avant stockage
    fun stripGps(file: File) {
        runCatching {
            val exif = ExifInterface(file)
            listOf(
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD
            ).forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(65_536)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
