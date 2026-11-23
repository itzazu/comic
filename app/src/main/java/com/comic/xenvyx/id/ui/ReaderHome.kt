package com.comic.xenvyx.id.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.comic.xenvyx.id.data.ReadingBackupManager
import com.comic.xenvyx.id.data.ReadingDatabase
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.comic.xenvyx.id.reader.KomikuWebView

private const val HOME_URL = "https://komiku.org/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderHome() {
    var url by remember { mutableStateOf(HOME_URL) }
    var showToolbar by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val backupManager = remember(context) {
        ReadingBackupManager(context.contentResolver, ReadingDatabase(context))
    }
    var backupStatus by remember { mutableStateOf("") }
    var askImportMode by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf(ReadingBackupManager.ImportMode.MERGE) }
    val createBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { backupManager.export(uri) } }
            backupStatus = if (result.isSuccess) "Backup berhasil disimpan." else "Backup gagal: ${result.exceptionOrNull()?.message}"
        }
    }
    val restoreBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { backupManager.import(uri, importMode) } }
            backupStatus = result.fold(
                { "Pemulihan berhasil: ${it.bookmarks.size} bookmark dan ${it.history.size} riwayat diproses." },
                { "Pemulihan gagal: ${it.message}" }
            )
        }
    }

    Scaffold(
        topBar = {
            if (showToolbar) TopAppBar(
                title = { Text(if (selectedTab == 0) "Komiku Reader" else if (selectedTab == 1) "Unduhan" else "Pengaturan") },
                actions = {
                    if (selectedTab == 0) IconButton(onClick = { url = HOME_URL }) {
                        Icon(Icons.Default.Home, contentDescription = "Beranda")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                    }
                }
            )
        },
        bottomBar = {
            if (showToolbar) NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Beranda") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = { Text("Unduhan") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2; showSettings = true },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Pengaturan") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> KomikuWebView(url, Modifier.fillMaxSize()) { webView = it }
                1 -> DownloadCenter()
                else -> ExtensionManagerPanel(Modifier.fillMaxSize())
            }
            if (!showToolbar && selectedTab == 0) {
                TextButton(onClick = { showToolbar = true }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("Tampilkan menu")
                }
            }
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("Pengaturan") },
        text = {
            Column {
                Text("Backup hanya mencakup bookmark dan riwayat baca. Cookie, kata sandi, dan token login tidak ikut disimpan.")
                TextButton(onClick = {
                    createBackupLauncher.launch("KomikuReader-backup-${System.currentTimeMillis()}.json")
                }) { Text("Backup sekarang") }
                TextButton(onClick = { askImportMode = true }) { Text("Pulihkan backup") }
                if (backupStatus.isNotBlank()) Text(backupStatus)
            }
        },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Tutup") } }
    )

    if (askImportMode) AlertDialog(
        onDismissRequest = { askImportMode = false },
        title = { Text("Mode pemulihan") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = importMode == ReadingBackupManager.ImportMode.MERGE,
                        onClick = { importMode = ReadingBackupManager.ImportMode.MERGE })
                    Text("Gabungkan (Merge), pertahankan data yang sudah ada")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = importMode == ReadingBackupManager.ImportMode.REPLACE,
                        onClick = { importMode = ReadingBackupManager.ImportMode.REPLACE })
                    Text("Ganti (Replace), hapus data lama sebelum pemulihan")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                askImportMode = false
                restoreBackupLauncher.launch(arrayOf("application/json", "text/json"))
            }) { Text("Pilih file backup") }
        },
        dismissButton = { TextButton(onClick = { askImportMode = false }) { Text("Batal") } }
    )
}
