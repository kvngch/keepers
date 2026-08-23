package fr.kvngch.keepers

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.kvngch.keepers.data.AppDb
import fr.kvngch.keepers.data.EncFile
import fr.kvngch.keepers.data.ItemEntity
import fr.kvngch.keepers.data.ItemFts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TypeFilter(val label: String) {
    ALL("Tous"), NOTES("Notes"), IMAGES("Images"), PDF("PDF"), OTHER("Autres")
}

enum class RangeFilter(val days: Long?, val label: String) {
    ALL(null, "Période : tout"), WEEK(7, "Période : 7 j"),
    MONTH(30, "Période : 30 j"), YEAR(365, "Période : 12 mois")
}

enum class Sort(val label: String) {
    RECENT("Tri : récent"), OLD("Tri : ancien"), TITLE("Tri : titre")
}

data class Filters(
    val type: TypeFilter = TypeFilter.ALL,
    val range: RangeFilter = RangeFilter.ALL,
    val sort: Sort = Sort.RECENT,
    val trash: Boolean = false
)

// semantic = trouve par similarite de sens, pas par les mots-cles
data class DisplayItem(val item: ItemEntity, val semantic: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDb.get(app).itemDao()
    private val docsDir = File(app.filesDir, "docs").apply { mkdirs() }

    val query = MutableStateFlow("")
    val filters = MutableStateFlow(Filters())
    private val processing = MutableStateFlow<String?>(null)

    // File de traitement : tous les elements pas encore indexes, dans l'ordre d'arrivee
    val pending: StateFlow<List<ItemEntity>> = dao.pending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val banner: StateFlow<String?> = combine(processing, pending) { p, queue ->
        p ?: when {
            queue.isEmpty() -> null
            else -> {
                val active = queue.firstOrNull { it.status.isNotBlank() && !it.status.startsWith("Erreur") }
                val head = active?.let { "${it.status.removeSuffix("...")} : ${it.title}" }
                    ?: "En attente d'indexation"
                if (queue.size == 1) head else "$head (+${queue.size - 1} en file)"
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: StateFlow<List<DisplayItem>> = combine(query, filters) { q, f -> q to f }
        .flatMapLatest { (q, f) ->
            when {
                f.trash -> dao.trash().map { l -> applyFilters(l, f).map { DisplayItem(it) } }
                q.isBlank() -> dao.all().map { l -> applyFilters(l, f).map { DisplayItem(it) } }
                else -> {
                    val match = ftsQuery(q)
                    val queryVec = withContext(Dispatchers.IO) {
                        Embedder.embed(getApplication(), q)
                    }
                    val ftsFlow = if (match.isBlank()) flowOf(emptyList()) else dao.searchFts(match)
                    combine(dao.all(), ftsFlow) { all, fts ->
                        val exact = applyFilters(fts, f)
                        val exactIds = exact.mapTo(HashSet()) { it.id }
                        val semantic = if (queryVec == null) emptyList() else {
                            applyFilters(all, f)
                                .asSequence()
                                .filter { it.id !in exactIds && it.embedding != null }
                                .map { it to Embedder.cosine(queryVec, Embedder.fromBytes(it.embedding!!)) }
                                .filter { it.second > 0.4f }
                                .sortedByDescending { it.second }
                                .take(10)
                                .map { it.first }
                                .toList()
                        }
                        exact.map { DisplayItem(it) } +
                            semantic.map { DisplayItem(it, semantic = true) }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // corbeille : purge definitive apres le delai configure
            val days = Prefs.trashDays(getApplication()).toLong()
            dao.purgeable(System.currentTimeMillis() - days * 86_400_000)
                .forEach { deleteForeverInternal(it) }
            // migration < 2.0.0 : chiffre les fichiers stockes en clair
            dao.unencryptedFiles().forEach { item ->
                runCatching {
                    EncFile.encryptInPlace(getApplication(), File(item.filePath!!))
                    dao.update(item.copy(fileEnc = true))
                }
            }
            // rattrapage < 2.2.0 : vectorise les elements indexes avant l'embedding
            for (item in dao.missingEmbedding()) {
                val vec = Embedder.embed(getApplication(), Indexer.embeddingText(item))
                    ?: break // modele indisponible, inutile d'insister
                dao.updateEmbedding(item.id, Embedder.toBytes(vec))
            }
        }
    }

    fun retryIndex(item: ItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setStatus(item.id, "")
            IndexWorker.enqueue(getApplication(), item.id)
        }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setType(t: TypeFilter) {
        filters.value = filters.value.copy(type = t)
    }

    fun cycleRange() {
        filters.value = filters.value.copy(range = next(RangeFilter.entries, filters.value.range))
    }

    fun cycleSort() {
        filters.value = filters.value.copy(sort = next(Sort.entries, filters.value.sort))
    }

    fun toggleTrash() {
        filters.value = filters.value.copy(trash = !filters.value.trash)
    }

    private fun <T> next(all: List<T>, cur: T): T = all[(all.indexOf(cur) + 1) % all.size]

    private fun applyFilters(list: List<ItemEntity>, f: Filters): List<ItemEntity> {
        var l = when (f.type) {
            TypeFilter.ALL -> list
            TypeFilter.NOTES -> list.filter { it.filePath == null }
            TypeFilter.IMAGES -> list.filter { Indexer.isImage(it.format) }
            TypeFilter.PDF -> list.filter { it.format == ".pdf" }
            TypeFilter.OTHER -> list.filter {
                it.filePath != null && !Indexer.isImage(it.format) && it.format != ".pdf"
            }
        }
        f.range.days?.let { d ->
            val min = System.currentTimeMillis() - d * 86_400_000
            l = l.filter { it.addedAt >= min }
        }
        return when (f.sort) {
            Sort.RECENT -> l.sortedByDescending { it.addedAt }
            Sort.OLD -> l.sortedBy { it.addedAt }
            Sort.TITLE -> l.sortedBy { it.title.lowercase() }
        }
    }

    private fun ftsQuery(raw: String): String = raw
        .split(Regex("\\s+"))
        .mapNotNull { t -> t.replace(Regex("[^\\p{L}\\p{N}]"), "").ifBlank { null } }
        .joinToString(" ") { "$it*" }

    fun newCaptureFile(): File = File(docsDir, "scan-${System.currentTimeMillis()}.jpg")

    fun addNote(title: String, body: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withBanner("Indexation de la note en cours...") {
                val a = Indexer.analyze(body)
                val cleanTitle = title.ifBlank { body.lineSequence().first().take(60) }
                val vec = Embedder.embed(getApplication(), "$cleanTitle ${body.take(1_000)}")
                val item = ItemEntity(
                    title = cleanTitle,
                    format = ".txt",
                    sizeBytes = body.toByteArray().size.toLong(),
                    addedAt = System.currentTimeMillis(),
                    summary = a.summary,
                    content = body,
                    indexed = true,
                    filePath = null,
                    extracted = a.extracted,
                    dueDate = a.dueDate,
                    embedding = vec?.let(Embedder::toBytes)
                )
                val id = dao.insert(item)
                dao.upsertFts(ItemFts(id, item.title, item.summary, item.content))
            }
        }
    }

    fun updateNote(item: ItemEntity, title: String, body: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val a = Indexer.analyze(body)
            val newTitle = title.ifBlank { item.title }
            val vec = Embedder.embed(getApplication(), "$newTitle ${body.take(1_000)}")
            val updated = item.copy(
                title = newTitle,
                content = body,
                summary = a.summary,
                extracted = a.extracted,
                dueDate = a.dueDate,
                sizeBytes = body.toByteArray().size.toLong(),
                embedding = vec?.let(Embedder::toBytes) ?: item.embedding
            )
            dao.update(updated)
            dao.upsertFts(ItemFts(item.id, updated.title, updated.summary, updated.content))
        }
    }

    fun importFiles(uris: List<Uri>) {
        uris.forEach { importFile(it) }
    }

    fun importFile(uri: Uri, defaultName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            withBanner("Copie locale du document...") {
                val resolver = getApplication<Application>().contentResolver
                var name = defaultName ?: "document-${System.currentTimeMillis()}"
                var size = 0L
                runCatching {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                        }
                    }
                }
                val dest = File(docsDir, "${System.currentTimeMillis()}-${name.replace('/', '_')}")
                val copied = runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } != null
                }.getOrDefault(false)
                if (!copied) {
                    dest.delete()
                    processing.value = "Échec de la lecture du fichier."
                    delay(1_500)
                    return@withBanner
                }
                if (size == 0L) size = dest.length()
                val ext = name.substringAfterLast('.', "").lowercase()
                ingest(
                    dest,
                    name.substringBeforeLast('.'),
                    if (ext.isBlank()) "fichier" else ".$ext",
                    size
                )
            }
        }
    }

    // Pages JPEG renvoyees par le scanner de documents
    fun importCapture(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            withBanner("Extraction des données du document en cours...") {
                val dest = newCaptureFile()
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                        dest.outputStream().use { i.copyTo(it) }
                    }
                }
                if (dest.exists() && dest.length() > 0) {
                    ingest(
                        dest,
                        "Capture du ${Formats.dateTime(System.currentTimeMillis())}",
                        ".jpg",
                        dest.length()
                    )
                }
            }
        }
    }

    fun ingestCapture(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            withBanner("Extraction des données du reçu en cours...") {
                ingest(
                    file,
                    "Capture du ${Formats.dateTime(System.currentTimeMillis())}",
                    ".jpg",
                    file.length()
                )
            }
        }
    }

    private suspend fun ingest(file: File, title: String, format: String, size: Long) {
        val sha = Indexer.sha256(file)
        if (dao.bySha(sha) != null) {
            file.delete()
            processing.value = "Document déjà présent dans le coffre."
            delay(1_500)
            return
        }
        if (format == ".jpg" || format == ".jpeg") Indexer.stripGps(file)
        EncFile.encryptInPlace(getApplication(), file)
        val item = ItemEntity(
            title = title,
            format = format,
            sizeBytes = size,
            addedAt = System.currentTimeMillis(),
            summary = "Indexation locale en cours...",
            content = "",
            indexed = false,
            filePath = file.absolutePath,
            sha256 = sha,
            fileEnc = true
        )
        val id = dao.insert(item)
        dao.upsertFts(ItemFts(id, title, "", ""))
        IndexWorker.enqueue(getApplication(), id)
    }

    fun moveToTrash(item: ItemEntity) = moveToTrash(listOf(item.id))

    fun moveToTrash(ids: Collection<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val t = System.currentTimeMillis()
            ids.forEach { dao.moveToTrash(it, t) }
        }
    }

    fun restoreFromTrash(item: ItemEntity) = restoreFromTrash(listOf(item.id))

    fun restoreFromTrash(ids: Collection<Long>) {
        viewModelScope.launch(Dispatchers.IO) { ids.forEach { dao.restoreFromTrash(it) } }
    }

    fun deleteForever(item: ItemEntity) = deleteForever(listOf(item.id))

    fun deleteForever(ids: Collection<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id -> dao.byId(id)?.let { deleteForeverInternal(it) } }
        }
    }

    private suspend fun deleteForeverInternal(item: ItemEntity) {
        item.filePath?.let { File(it).delete() }
        dao.deleteFts(item.id)
        dao.delete(item)
    }

    fun exportVault(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            processing.value = "Export chiffré du coffre en cours..."
            val result = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    Vault.export(getApplication(), dao, out, password.toCharArray())
                }
            }
            processing.value = if (result.isSuccess) "Export terminé." else "Échec de l'export."
            delay(2_000)
            processing.value = null
        }
    }

    fun restoreVault(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            processing.value = "Restauration du coffre en cours..."
            val result = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
                    Vault.restore(getApplication(), dao, ins, password.toCharArray(), docsDir)
                } ?: 0
            }
            processing.value = result.fold(
                { "$it éléments restaurés." },
                { "Échec de la restauration (mot de passe ?)." }
            )
            delay(2_500)
            processing.value = null
        }
    }

    private suspend fun withBanner(message: String, block: suspend () -> Unit) {
        processing.value = message
        try {
            block()
            delay(800)
        } finally {
            processing.value = null
        }
    }
}
