@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package fr.kvngch.keepers.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import fr.kvngch.keepers.Formats
import fr.kvngch.keepers.Indexer
import fr.kvngch.keepers.MainViewModel
import fr.kvngch.keepers.Prefs
import fr.kvngch.keepers.Sort
import fr.kvngch.keepers.TypeFilter
import fr.kvngch.keepers.data.EncFile
import fr.kvngch.keepers.data.ItemEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.padding(12.dp))
            Text("Coffre verrouillé", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.padding(4.dp))
            Text(
                "Vos documents restent chiffrés sur cet appareil.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.padding(12.dp))
            Button(onClick = onUnlock) { Text("Déverrouiller") }
        }
    }
}

@Composable
fun MainScreen(vm: MainViewModel, startAction: String?) {
    val context = LocalContext.current
    val docs by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val f by vm.filters.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()

    var showNoteDialog by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<ItemEntity?>(null) }
    var detailItem by remember { mutableStateOf<ItemEntity?>(null) }
    var viewerItem by remember { mutableStateOf<ItemEntity?>(null) }
    var trashItem by remember { mutableStateOf<ItemEntity?>(null) }
    var pendingCapture by remember { mutableStateOf<File?>(null) }
    var exportAsk by remember { mutableStateOf(false) }
    var restoreAskUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingExportPw by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    LaunchedEffect(f.trash) { selected = emptySet() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.importFiles(uris) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = pendingCapture
        if (ok && file != null) vm.ingestCapture(file) else file?.delete()
        pendingCapture = null
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val pdf = result?.pdf
            val pages = result?.pages.orEmpty()
            if (pdf != null && pages.size > 1) {
                vm.importFile(
                    pdf.uri,
                    "Scan du ${Formats.dateTime(System.currentTimeMillis())}.pdf"
                )
            } else {
                pages.forEach { vm.importCapture(it.imageUri) }
            }
        }
    }

    val fallbackCamera = {
        val file = vm.newCaptureFile()
        pendingCapture = file
        cameraLauncher.launch(
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        )
    }

    val startScan = {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(context as Activity)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { fallbackCamera() }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pw = pendingExportPw
        if (uri != null && pw != null) vm.exportVault(uri, pw)
        pendingExportPw = null
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreAskUri = uri }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        when (startAction) {
            "scan" -> startScan()
            "note" -> showNoteDialog = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            IngestFab(
                onNote = { showNoteDialog = true },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onCapture = {
                    if (Prefs.useScanner(context)) startScan() else fallbackCamera()
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selected.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selected = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Annuler la sélection")
                    }
                    Text(
                        "${selected.size} sélectionné(s)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (f.trash) {
                        IconButton(onClick = {
                            vm.restoreFromTrash(selected)
                            selected = emptySet()
                        }) {
                            Icon(
                                Icons.Default.RestoreFromTrash,
                                contentDescription = "Restaurer la sélection"
                            )
                        }
                        IconButton(onClick = { confirmBatchDelete = true }) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = "Supprimer définitivement la sélection",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            vm.moveToTrash(selected)
                            selected = emptySet()
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Mettre la sélection à la corbeille"
                            )
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Keepers",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Réglages") },
                            onClick = {
                                menuOpen = false
                                showSettings = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exporter le coffre") },
                            onClick = {
                                menuOpen = false
                                exportAsk = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Restaurer une sauvegarde") },
                            onClick = {
                                menuOpen = false
                                restorePicker.launch(arrayOf("*/*"))
                            }
                        )
                    }
                }
            }

            SearchField(query, vm::setQuery)

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(TypeFilter.entries) { t ->
                    FilterChip(
                        selected = f.type == t,
                        onClick = { vm.setType(t) },
                        label = { Text(t.label) }
                    )
                }
                item {
                    FilterChip(
                        selected = f.range.days != null,
                        onClick = { vm.cycleRange() },
                        label = { Text(f.range.label) }
                    )
                }
                item {
                    FilterChip(
                        selected = f.sort != Sort.RECENT,
                        onClick = { vm.cycleSort() },
                        label = { Text(f.sort.label) }
                    )
                }
                item {
                    FilterChip(
                        selected = f.trash,
                        onClick = { vm.toggleTrash() },
                        label = { Text("Corbeille") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            var bannerText by remember { mutableStateOf("") }
            banner?.let { bannerText = it }
            AnimatedVisibility(visible = banner != null) {
                ProcessingBanner(bannerText)
            }

            if (docs.isEmpty()) {
                EmptyState(searching = query.isNotBlank(), trash = f.trash)
            } else {
                val grouped = remember(docs, f.sort) {
                    if (f.sort == Sort.TITLE) linkedMapOf("" to docs)
                    else docs.groupByTo(LinkedHashMap()) { Formats.month(it.addedAt) }
                }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    grouped.forEach { (month, list) ->
                        if (month.isNotBlank()) {
                            item(key = "month-$month") {
                                Text(
                                    month,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                )
                            }
                        }
                        items(list, key = { it.id }) { item ->
                            ItemCard(
                                item,
                                isSelected = item.id in selected,
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (item.id in selected) selected - item.id
                                        else selected + item.id
                                    } else when {
                                        f.trash -> trashItem = item
                                        item.filePath == null -> detailItem = item
                                        else -> viewerItem = item
                                    }
                                },
                                onLongClick = {
                                    selected = if (item.id in selected) selected - item.id
                                    else selected + item.id
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNoteDialog) {
        NoteDialog(
            onDismiss = { showNoteDialog = false },
            onSave = { title, body ->
                vm.addNote(title, body)
                showNoteDialog = false
            }
        )
    }

    editItem?.let { item ->
        NoteDialog(
            initialTitle = item.title,
            initialBody = item.content,
            onDismiss = { editItem = null },
            onSave = { title, body ->
                vm.updateNote(item, title, body)
                editItem = null
            }
        )
    }

    detailItem?.let { item ->
        DetailDialog(
            item = item,
            onDismiss = { detailItem = null },
            onDelete = {
                vm.moveToTrash(item)
                detailItem = null
            },
            onEdit = {
                detailItem = null
                editItem = item
            }
        )
    }

    viewerItem?.let { item ->
        ViewerDialog(
            item = item,
            onDismiss = { viewerItem = null },
            onDelete = {
                vm.moveToTrash(item)
                viewerItem = null
            }
        )
    }

    trashItem?.let { item ->
        AlertDialog(
            onDismissRequest = { trashItem = null },
            title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("Élément dans la corbeille. Il sera supprimé définitivement 30 jours après sa mise à la corbeille.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.restoreFromTrash(item)
                    trashItem = null
                }) {
                    Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Restaurer")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.deleteForever(item)
                    trashItem = null
                }) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Supprimer définitivement", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    if (exportAsk) {
        PasswordDialog(
            title = "Exporter le coffre",
            hint = "Mot de passe de la sauvegarde (6 caractères minimum). Il sera indispensable pour restaurer.",
            onDismiss = { exportAsk = false },
            onConfirm = { pw ->
                exportAsk = false
                pendingExportPw = pw
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.FRANCE).format(Date())
                exportLauncher.launch("keepers-$stamp.keepers")
            }
        )
    }

    restoreAskUri?.let { uri ->
        PasswordDialog(
            title = "Restaurer une sauvegarde",
            hint = "Mot de passe utilisé lors de l'export.",
            onDismiss = { restoreAskUri = null },
            onConfirm = { pw ->
                vm.restoreVault(uri, pw)
                restoreAskUri = null
            }
        )
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }

    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("Supprimer définitivement ?") },
            text = { Text("${selected.size} élément(s) seront supprimés sans possibilité de récupération.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteForever(selected)
                    selected = emptySet()
                    confirmBatchDelete = false
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        placeholder = {
            Text(
                "Rechercher un contrat, une facture, une note...",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Effacer la recherche")
                    }
                }
                LocalBadge()
                Spacer(Modifier.width(12.dp))
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun LocalBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = "Traitement 100 % local",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(12.dp)
            )
            Text(
                "LOCAL",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ProcessingBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ItemCard(
    item: ItemEntity,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val thumbBmp = remember(item.id, item.thumb) {
                item.thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (thumbBmp != null) {
                Image(
                    thumbBmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    iconFor(item.format),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.extracted.isNotBlank()) {
                    Text(
                        item.extracted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Formats.date(item.addedAt)}  ${item.format}  ${Formats.size(item.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    val due = item.dueDate
                    if (due != null && due > System.currentTimeMillis()) {
                        DueBadge(due)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (item.indexed) IndexedBadge() else PendingBadge()
                }
            }
        }
    }
}

private fun iconFor(format: String): ImageVector = when {
    Indexer.isImage(format) -> Icons.Default.ImageIcon
    format == ".pdf" -> Icons.Default.Description
    format == ".txt" || format == ".md" -> Icons.Default.EditNote
    else -> Icons.Default.InsertDriveFile
}

@Composable
private fun DueBadge(due: Long) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Échéance",
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(12.dp)
            )
            Text(
                Formats.date(due),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun IndexedBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(12.dp)
            )
            Text(
                "Indexé",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun PendingBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Indexation",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(searching: Boolean, trash: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.padding(8.dp))
        Text(
            when {
                trash -> "La corbeille est vide."
                searching -> "Aucun résultat pour cette recherche."
                else -> "Votre coffre est vide.\nAjoutez une note, un document ou une capture avec le bouton +."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun IngestFab(
    onNote: () -> Unit,
    onImport: () -> Unit,
    onCapture: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(expanded) {
            FabAction("Capturer un document", Icons.Default.PhotoCamera) {
                expanded = false
                onCapture()
            }
        }
        AnimatedVisibility(expanded) {
            FabAction("Importer des fichiers", Icons.Default.UploadFile) {
                expanded = false
                onImport()
            }
        }
        AnimatedVisibility(expanded) {
            FabAction("Note rapide", Icons.Default.EditNote) {
                expanded = false
                onNote()
            }
        }
        FloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                if (expanded) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (expanded) "Fermer le menu d'ajout" else "Ajouter un élément"
            )
        }
    }
}

@Composable
private fun FabAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp
        ) {
            Text(
                label,
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun NoteDialog(
    initialTitle: String = "",
    initialBody: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var body by remember { mutableStateOf(initialBody) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialBody.isBlank()) "Note rapide" else "Modifier la note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titre (optionnel)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Contenu") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), body.trim()) },
                enabled = body.isNotBlank()
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
internal fun PasswordDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password) },
                enabled = password.length >= 6
            ) { Text("Continuer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun DetailDialog(
    item: ItemEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Ajouté le ${Formats.dateTime(item.addedAt)}  ${item.format}  ${Formats.size(item.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    item.content.ifBlank { item.summary },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onEdit) { Text("Modifier") }
            }
        }
    )
}

@Composable
private fun ViewerDialog(
    item: ItemEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer")
                    }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Mettre à la corbeille",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    when {
                        Indexer.isImage(item.format) -> ZoomableImage(item)
                        item.format == ".pdf" -> PdfPages(item)
                        else -> Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Text(
                                item.content.ifBlank { item.summary },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(item: ItemEntity) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = EncFile.decryptBytes(context, File(item.filePath!!))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                    sample *= 2
                }
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset += pan
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bmp.asImageBitmap(),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun PdfPages(item: ItemEntity) {
    val context = LocalContext.current
    val mutex = remember { Mutex() }
    var pageCount by remember { mutableStateOf(0) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var temp by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(item.id) {
        withContext(Dispatchers.IO) {
            runCatching {
                val plain = EncFile.decryptToCache(context, File(item.filePath!!))
                temp = plain
                val r = PdfRenderer(
                    ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY)
                )
                renderer = r
                pageCount = r.pageCount
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { renderer?.close() }
            temp?.delete()
        }
    }

    if (pageCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(pageCount) { idx ->
            val page by produceState<Bitmap?>(null, idx, renderer) {
                value = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        runCatching {
                            renderer?.let { r ->
                                val p = r.openPage(idx)
                                try {
                                    val w = 1080
                                    val h = (p.height.toFloat() / p.width * w).toInt()
                                        .coerceAtLeast(1)
                                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    b.eraseColor(android.graphics.Color.WHITE)
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    b
                                } finally {
                                    p.close()
                                }
                            }
                        }.getOrNull()
                    }
                }
            }
            val bmp = page
            if (bmp == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = "Page ${idx + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
