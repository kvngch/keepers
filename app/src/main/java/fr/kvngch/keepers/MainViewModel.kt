package fr.kvngch.keepers

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.kvngch.keepers.data.AppDb
import fr.kvngch.keepers.data.ItemEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDb.get(app).itemDao()
    private val docsDir = File(app.filesDir, "docs").apply { mkdirs() }

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
                // ponytail: extraction texte brut seulement; OCR et parsing PDF a ajouter
                // si le besoin se confirme (ML Kit / pdfbox-android, tout reste on-device)
                val content = if (textual && size <= 5_000_000) {
                    runCatching { dest.readText().take(100_000) }.getOrDefault("")
                } else ""

                processing.value = "Extraction des données du document en cours..."
                dao.insert(
                    ItemEntity(
                        title = name.substringBeforeLast('.'),
                        format = if (ext.isBlank()) "fichier" else ".$ext",
                        sizeBytes = size,
                        addedAt = System.currentTimeMillis(),
                        summary = if (content.isNotBlank()) summarize(content)
                        else "Fichier stocké localement. Métadonnées indexées, contenu non extrait.",
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
                dao.insert(
                    ItemEntity(
                        title = "Capture du ${Formats.dateTime(System.currentTimeMillis())}",
                        format = ".jpg",
                        sizeBytes = file.length(),
                        addedAt = System.currentTimeMillis(),
                        summary = "Capture photo stockée localement. Métadonnées indexées.",
                        content = "",
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
