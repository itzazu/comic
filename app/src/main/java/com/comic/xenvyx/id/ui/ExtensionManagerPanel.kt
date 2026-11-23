package com.comic.xenvyx.id.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.comic.xenvyx.id.extension.ExtensionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExtensionManagerPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember(context) { ExtensionManager(context) }
    val scope = rememberCoroutineScope()
    var extensions by remember { mutableStateOf(manager.list()) }
    var status by remember { mutableStateOf("Import ZIP, JS, atau CRX. APK dikenali tetapi tidak dapat dipasang sebagai extension browser.") }
    var pendingDelete by remember { mutableStateOf<ExtensionManager.Extension?>(null) }
    fun refresh() { extensions = manager.list() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { manager.`import`(uri) } }
            status = result.fold(
                { "${it.extension.name} ${it.extension.version} berhasil diimpor. ${it.warning}" },
                { "Import gagal: ${it.message ?: "format tidak valid"}" }
            )
            refresh()
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Extension Manager", style = MaterialTheme.typography.headlineSmall)
        Text("Pasang content script untuk menambahkan dukungan situs. Extension baru selalu nonaktif sampai kamu mengaktifkannya.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/javascript", "application/javascript", "application/x-chrome-extension", "application/vnd.android.package-archive", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import extension")
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Divider()
        if (extensions.isEmpty()) {
            Text("Belum ada extension yang dipasang.", modifier = Modifier.padding(vertical = 12.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(extensions, key = { it.id }) { ext ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(ext.name, style = MaterialTheme.typography.titleMedium)
                                    Text("v${ext.version} • ${ext.format}", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = ext.enabled, onCheckedChange = { enabled ->
                                    runCatching { manager.setEnabled(ext.id, enabled) }
                                        .onSuccess { refresh(); status = if (enabled) "${ext.name} diaktifkan." else "${ext.name} dinonaktifkan." }
                                        .onFailure { status = "Gagal mengubah status: ${it.message}" }
                                })
                            }
                            if (ext.description.isNotBlank()) Text(ext.description, style = MaterialTheme.typography.bodySmall)
                            Text("Situs: ${ext.matches.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            Text("Script: ${ext.scripts.size}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { pendingDelete = ext }) { Text("Hapus") }
                            }
                        }
                    }
                }
            }
        }
        Text("Keamanan: script extension berjalan di konteks halaman situs yang cocok. Hanya pasang file dari sumber tepercaya. Extension tidak memperoleh Android bridge, tetapi script halaman tetap dapat membaca DOM dan menggunakan jaringan sesuai kemampuan halaman.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    pendingDelete?.let { ext ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus extension?") },
            text = { Text("${ext.name} akan dihapus dari penyimpanan aplikasi.") },
            confirmButton = { TextButton(onClick = {
                runCatching { manager.remove(ext.id) }.onSuccess { status = "${ext.name} dihapus." }.onFailure { status = "Gagal menghapus: ${it.message}" }
                pendingDelete = null; refresh()
            }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } }
        )
    }
}
