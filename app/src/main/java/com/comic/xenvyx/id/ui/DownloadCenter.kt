package com.comic.xenvyx.id.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comic.xenvyx.id.download.DownloadJobStore
import com.comic.xenvyx.id.download.DownloadScheduler
import com.comic.xenvyx.id.download.OfflineChapterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun DownloadCenter() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scheduler = remember(context) { DownloadScheduler(context) }
    val offlineManager = remember(context) { OfflineChapterManager(context) }
    var jobs by remember { mutableStateOf(emptyList<DownloadJobStore.Job>()) }
    var offline by remember { mutableStateOf(emptyList<OfflineChapterManager.OfflineChapter>()) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            loading = true
            val snapshot = withContext(Dispatchers.IO) { scheduler.jobs() to offlineManager.list() }
            jobs = snapshot.first
            offline = snapshot.second
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Unduhan", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Muat ulang") }
        }
        if (loading) Text("Memuat daftar unduhan…", style = MaterialTheme.typography.bodyMedium)
        else if (jobs.isEmpty()) Text("Belum ada chapter di antrean unduhan.")

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id }) { job ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${job.comicId} • ${job.chapterId}", style = MaterialTheme.typography.titleMedium)
                        Text(statusLabel(job.status), style = MaterialTheme.typography.bodyMedium)
                        Text("${job.completedCount} / ${job.imageCount} gambar", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (job.status == DownloadJobStore.STATUS_QUEUED || job.status == DownloadJobStore.STATUS_RUNNING) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { scheduler.cancel(job.id) }
                                        refresh()
                                    }
                                }) {
                                    Icon(Icons.Default.Cancel, contentDescription = null)
                                    Text("Batalkan", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                            if (job.status == DownloadJobStore.STATUS_COMPLETE ||
                                job.status == DownloadJobStore.STATUS_FAILED ||
                                job.status == DownloadJobStore.STATUS_CANCELLED) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { offlineManager.delete(job.id) }
                                        refresh()
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                    Text("Hapus offline", modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (offline.isNotEmpty()) {
                item { Text("Penyimpanan offline", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
                items(offline, key = { "offline-${it.jobId}" }) { chapter ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${chapter.comicId} • ${chapter.chapterId}", style = MaterialTheme.typography.titleMedium)
                            Text("${chapter.imageCount} gambar • ${formatBytes(chapter.totalBytes)}")
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    DownloadJobStore.STATUS_QUEUED -> "Dalam antrean"
    DownloadJobStore.STATUS_RUNNING -> "Sedang diunduh"
    DownloadJobStore.STATUS_COMPLETE -> "Selesai"
    DownloadJobStore.STATUS_FAILED -> "Gagal"
    DownloadJobStore.STATUS_CANCELLED -> "Dibatalkan"
    else -> status
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
