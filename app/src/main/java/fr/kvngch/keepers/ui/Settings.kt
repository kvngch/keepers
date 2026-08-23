package fr.kvngch.keepers.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.kvngch.keepers.BackupWorker
import fr.kvngch.keepers.Prefs

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var grace by remember { mutableIntStateOf(Prefs.lockGraceSeconds(context)) }
    var trashDays by remember { mutableIntStateOf(Prefs.trashDays(context)) }
    var pdfPages by remember { mutableIntStateOf(Prefs.pdfMaxPages(context)) }
    var scanner by remember { mutableStateOf(Prefs.useScanner(context)) }
    var autoBackup by remember { mutableStateOf(Prefs.autoBackup(context)) }
    var backupTree by remember { mutableStateOf(Prefs.backupTree(context)) }
    var askBackupPw by remember { mutableStateOf(false) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.setBackupTree(context, uri.toString())
            backupTree = uri.toString()
            if (autoBackup) BackupWorker.schedule(context)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fermer les réglages")
                    }
                    Text("Réglages", style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    SettingRow(
                        "Verrouillage après retour",
                        when (grace) {
                            0 -> "immédiat"
                            60 -> "1 min"
                            300 -> "5 min"
                            else -> "30 min"
                        }
                    ) {
                        grace = when (grace) {
                            0 -> 60
                            60 -> 300
                            300 -> 1800
                            else -> 0
                        }
                        Prefs.setLockGraceSeconds(context, grace)
                    }
                    SettingRow("Rétention de la corbeille", "$trashDays jours") {
                        trashDays = when (trashDays) {
                            7 -> 30
                            30 -> 90
                            else -> 7
                        }
                        Prefs.setTrashDays(context, trashDays)
                    }
                    SettingRow("Pages PDF indexées", "$pdfPages") {
                        pdfPages = when (pdfPages) {
                            5 -> 10
                            10 -> 25
                            25 -> 50
                            else -> 5
                        }
                        Prefs.setPdfMaxPages(context, pdfPages)
                    }
                    SwitchRow(
                        "Scanner de documents",
                        "Recadrage automatique via Play services, sinon appareil photo",
                        scanner
                    ) {
                        scanner = it
                        Prefs.setUseScanner(context, it)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "SAUVEGARDE AUTOMATIQUE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    SwitchRow(
                        "Sauvegarde hebdomadaire chiffrée",
                        "Export complet dans le dossier choisi, 4 dernières conservées",
                        autoBackup
                    ) { enabled ->
                        if (enabled) {
                            askBackupPw = true
                        } else {
                            autoBackup = false
                            Prefs.setAutoBackup(context, false)
                            BackupWorker.cancel(context)
                        }
                    }
                    SettingRow(
                        "Dossier de sauvegarde",
                        if (backupTree == null) "non défini" else "défini"
                    ) {
                        treePicker.launch(null)
                    }
                }
            }
        }
    }

    if (askBackupPw) {
        PasswordDialog(
            title = "Mot de passe des sauvegardes",
            hint = "Il chiffrera chaque sauvegarde automatique et sera indispensable pour restaurer. Conservez-le ailleurs que sur ce téléphone.",
            onDismiss = { askBackupPw = false },
            onConfirm = { pw ->
                Prefs.setBackupPassword(context, pw)
                Prefs.setAutoBackup(context, true)
                autoBackup = true
                askBackupPw = false
                if (backupTree == null) treePicker.launch(null)
                BackupWorker.schedule(context)
            }
        )
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
