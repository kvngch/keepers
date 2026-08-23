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

enum class Category(val id: String, val label: String) {
    FACTURE("facture", "Factures"),
    RECU("recu", "Reçus"),
    CONTRAT("contrat", "Contrats"),
    GARANTIE("garantie", "Garanties"),
    BANQUE("banque", "Banque"),
    IMPOTS("impots", "Impôts"),
    SANTE("sante", "Santé"),
    IDENTITE("identite", "Identité"),
    ASSURANCE("assurance", "Assurance"),
    NOTE("note", "Notes"),
    AUTRE("autre", "Autres");

    companion object {
        fun byId(id: String): Category = entries.firstOrNull { it.id == id } ?: AUTRE
    }
}

// Toute l'analyse locale d'un document : OCR, PDF, resume extractif, dates et montants,
// miniature. Utilise par IndexWorker (fichiers) et par le ViewModel (notes).
object Indexer {

    private val IMAGE_EXT = setOf(".jpg", ".jpeg", ".png", ".webp", ".bmp")
    private val TEXT_EXT = setOf(".txt", ".md", ".csv", ".json", ".log")

    fun isImage(format: String): Boolean = format in IMAGE_EXT

    data class Analysis(
        val summary: String,
        val extracted: String,
        val dueDate: Long?,
        val docDate: Long?
    )

    suspend fun index(
        context: Context,
        dao: ItemDao,
        item: ItemEntity,
        recognizer: TextRecognizer,
        maxPdfPages: Int = 10
    ) {
        val src = item.filePath?.let { File(it) } ?: return
        if (!src.exists()) return
        dao.setStatus(
            item.id,
            when {
                item.format == ".pdf" -> "OCR des pages du PDF..."
                isImage(item.format) -> "OCR de l'image..."
                item.format in TEXT_EXT -> "Lecture du texte..."
                else -> "Indexation des métadonnées..."
            }
        )
        val plain = EncFile.decryptToCache(context, src)
        try {
            val content = when {
                item.format in TEXT_EXT ->
                    runCatching { plain.readText().take(100_000) }.getOrDefault("")
                item.format == ".pdf" -> pdfText(plain, recognizer, maxPdfPages)
                isImage(item.format) -> ocrFile(context, plain, recognizer)
                else -> ""
            }
            dao.setStatus(item.id, "Analyse : dates, montants, résumé...")
            val a = analyze(content)
            val summary = when {
                content.isNotBlank() -> a.summary
                isImage(item.format) || item.format == ".pdf" ->
                    "Document stocké localement, aucun texte détecté par l'OCR."
                else -> "Fichier stocké localement. Métadonnées indexées, contenu non extrait."
            }
            val thumb = thumbnail(plain, item.format)
            dao.setStatus(item.id, "Vectorisation sémantique...")
            val embedding = Embedder.embed(context, item.title + " " + content.take(1_000))
            val updated = item.copy(
                content = content,
                summary = summary,
                extracted = a.extracted,
                dueDate = a.dueDate,
                docDate = a.docDate,
                thumb = thumb,
                embedding = embedding?.let(Embedder::toBytes),
                category = if (item.catManual) item.category
                else classify(item.title, content, isNote = false).id,
                status = "",
                indexed = true
            )
            dao.update(updated)
            dao.upsertFts(ftsRow(updated))
        } finally {
            plain.delete()
        }
    }

    fun embeddingText(item: ItemEntity): String =
        item.title + " " + item.content.take(1_000)

    // Les tags sont concatenes au contenu FTS pour etre cherchables
    fun ftsRow(item: ItemEntity): ItemFts =
        ItemFts(item.id, item.title, item.summary, (item.content + " " + item.tags).trim())

    // Classification par mots-cles ponderes sur le texte extrait.
    // ponytail: heuristique lexicale, remplacable par le classifieur semantique
    // (cosinus avec des phrases prototypes par categorie) si elle se revele trop fragile
    private val KEYWORDS: Map<Category, List<Pair<String, Int>>> = mapOf(
        Category.FACTURE to listOf(
            "facture" to 3, "tva" to 2, "ttc" to 2, "net à payer" to 3, "montant dû" to 3
        ),
        Category.RECU to listOf(
            "reçu" to 3, "ticket de caisse" to 3, "total payé" to 3,
            "espèces" to 2, "carte bancaire" to 2
        ),
        Category.CONTRAT to listOf(
            "contrat" to 3, "conditions générales" to 3, "soussigné" to 3,
            "bail" to 3, "résiliation" to 2, "les parties" to 2
        ),
        Category.GARANTIE to listOf(
            "garantie" to 3, "warranty" to 3, "extension de garantie" to 3
        ),
        Category.BANQUE to listOf(
            "relevé de compte" to 3, "virement" to 2, "solde" to 2, "iban" to 2, "banque" to 2
        ),
        Category.IMPOTS to listOf(
            "impôt" to 3, "dgfip" to 3, "avis d'imposition" to 3,
            "prélèvement à la source" to 3, "taxe" to 2, "fiscal" to 2
        ),
        Category.SANTE to listOf(
            "ordonnance" to 3, "cpam" to 3, "mutuelle" to 2, "pharmacie" to 2,
            "docteur" to 2, "sécurité sociale" to 2
        ),
        Category.IDENTITE to listOf(
            "passeport" to 3, "carte nationale d'identité" to 3, "permis de conduire" to 3
        ),
        Category.ASSURANCE to listOf(
            "assurance" to 3, "sinistre" to 3, "assuré" to 2, "attestation" to 1
        )
    )

    fun classify(title: String, content: String, isNote: Boolean): Category {
        if (isNote) return Category.NOTE
        val text = "$title $content".lowercase()
        var best = Category.AUTRE
        var bestScore = 0
        for ((cat, words) in KEYWORDS) {
            val score = words.sumOf { (w, weight) -> if (text.contains(w)) weight else 0 }
            if (score > bestScore) {
                bestScore = score
                best = cat
            }
        }
        return if (bestScore >= 3) best else Category.AUTRE
    }

    // Resume extractif local : les deux phrases les plus denses en mots frequents.
    // ponytail: heuristique simple; Gemini Nano (AICore) si un appareil compatible arrive.
    fun analyze(text: String): Analysis {
        if (text.isBlank()) return Analysis("", "", null, null)
        val now = System.currentTimeMillis()
        val dates = detectDates(text)
        val due = dates.filter { it > now }.minOrNull()
        // date du document : la date passee la plus recente trouvee dans le texte
        val docDate = dates.filter { it <= now }.maxOrNull()
        val amounts = AMOUNT.findAll(text).map { it.value.trim() }.distinct().take(3).toList()
        val ibans = IBAN.findAll(text).map { it.value.replace(" ", "") }.distinct().take(1).toList()
        val dateStrs = dates.sorted().map { Formats.date(it) }.distinct().take(3)
        val extracted = (amounts + dateStrs + ibans).joinToString(" · ")
        return Analysis(summarize(text), extracted, due, docDate)
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
