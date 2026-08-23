package fr.kvngch.keepers

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import fr.kvngch.keepers.data.AppDb
import fr.kvngch.keepers.data.ItemEntity
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDb.get(app).itemDao()
    private val docsDir = File(app.filesDir, "docs").apply { mkdirs() }
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    val query = MutableStateFlow("")
    val processing = MutableStateFlow<String?>(null)

    val items: StateFlow<List<ItemEntity>> = query
        .flatMapLatest { q -> if (q.isBlank()) dao.all() else dao.search(q.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        query.value = q
    }

    fun newCaptureFile(): File = File(docsDir, "scan-${System.currentTimeMillis()}.jpg")

    fun addNote(title: String, body: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withBanner("Indexation de la note en cours...") {
                val cleanTitle = title.ifBlank { body.lineSequence().first().take(60) }
                dao.insert(
                    ItemEntity(
                        title = cleanTitle,
                        format = ".txt",
                        sizeBytes = body.toByteArray().size.toLong(),
                        addedAt = System.currentTimeMillis(),
                        summary = summarize(body),
                        content = body,
                        indexed = true,
                        filePath = null
                    )
                )
            }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            withBanner("Copie et analyse locales du fichier...") {
                val resolver = getApplication<Application>().contentResolver
                var name = "document"
                var size = 0L
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val dest = File(docsDir, "${System.currentTimeMillis()}-$name")
                resolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                if (size == 0L) size = dest.length()

                val mime = resolver.getType(uri) ?: ""
                val textual = mime.startsWith("text/") ||
                    ext in setOf("txt", "md", "csv", "json", "log")
                val image = mime.startsWith("image/") ||
                    ext in setOf("jpg", "jpeg", "png", "webp", "bmp")
                val pdf = mime == "application/pdf" || ext == "pdf"
                val content = when {
                    textual && size <= 5_000_000 ->
                        runCatching { dest.readText().take(100_000) }.getOrDefault("")
                    pdf -> {
                        processing.value = "Analyse des pages du PDF (OCR local) en cours..."
                        pdfText(dest)
                    }
                    image -> {
                        processing.value = "Reconnaissance du texte (OCR local) en cours..."
                        ocr(dest)
                    }
                    else -> ""
                }

                processing.value = "Extraction des données du document en cours..."
                dao.insert(
                    ItemEntity(
                        title = name.substringBeforeLast('.'),
                        format = if (ext.isBlank()) "fichier" else ".$ext",
                        sizeBytes = size,
                        addedAt = System.currentTimeMillis(),
                        summary = when {
                            content.isNotBlank() -> summarize(content)
                            image || pdf -> "Document stocké localement, aucun texte détecté par l'OCR."
                            else -> "Fichier stocké localement. Métadonnées indexées, contenu non extrait."
                        },
                        content = content,
                        indexed = true,
                        filePath = dest.absolutePath
                    )
                )
            }
        }
    }

    fun ingestCapture(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            withBanner("Extraction des données du reçu en cours...") {
                val content = ocr(file)
                dao.insert(
                    ItemEntity(
                        title = "Capture du ${Formats.dateTime(System.currentTimeMillis())}",
                        format = ".jpg",
                        sizeBytes = file.length(),
                        addedAt = System.currentTimeMillis(),
                        summary = if (content.isNotBlank()) summarize(content)
                        else "Capture photo stockée localement, aucun texte détecté par l'OCR.",
                        content = content,
                        indexed = true,
                        filePath = file.absolutePath
                    )
                )
            }
        }
    }

    fun delete(item: ItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            item.filePath?.let { File(it).delete() }
            dao.delete(item)
        }
    }

    private suspend fun ocr(file: File): String = suspendCancellableCoroutine { cont ->
        val input = runCatching {
            InputImage.fromFilePath(getApplication(), Uri.fromFile(file))
        }.getOrNull()
        if (input == null) {
            cont.resume("")
            return@suspendCancellableCoroutine
        }
        recognizer.process(input)
            .addOnSuccessListener { cont.resume(it.text.take(100_000)) }
            .addOnFailureListener { cont.resume("") }
    }

    private suspend fun ocrBitmap(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume("") }
        }

    // PDF numerique ou scanne : chaque page est rendue par PdfRenderer puis passee a
    // l'OCR local, aucune dependance de parsing supplementaire.
    private suspend fun pdfText(file: File): String {
        val sb = StringBuilder()
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    // ponytail: cap a 10 pages pour borner temps et memoire; a relever
                    // si des documents longs doivent etre indexes en entier
                    val pages = minOf(renderer.pageCount, 10)
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
                            val matrix = Matrix().apply { setScale(scale, scale) }
                            page.render(
                                bitmap, null, matrix,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            sb.append(ocrBitmap(bitmap)).append('\n')
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

    override fun onCleared() {
        recognizer.close()
    }

    private fun summarize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(160)

    private suspend fun withBanner(message: String, block: suspend () -> Unit) {
        processing.value = message
        try {
            block()
            delay(1_200)
        } finally {
            processing.value = null
        }
    }
}
