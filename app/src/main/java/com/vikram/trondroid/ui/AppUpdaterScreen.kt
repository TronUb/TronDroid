package com.vikram.trondroid.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vikram.trondroid.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class ReleaseInfo(val tag: String, val name: String, val notes: String, val apkUrl: String, val publishedAt: String)

@Composable
fun AppUpdaterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var message by remember { mutableStateOf("Check GitHub for the latest TronDroid build.") }
    var loading by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloadedUri by remember { mutableStateOf<Uri?>(null) }

    fun checkUpdates() {
        loading = true
        message = "Checking the latest release…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL("https://api.github.com/repos/TronUb/TronDroid/releases/tags/debug-latest")
                        .openConnection() as HttpURLConnection
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "TronDroid-Android-Updater")
                    try {
                        if (connection.responseCode !in 200..299) error("GitHub returned HTTP ${connection.responseCode}")
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val assets = json.getJSONArray("assets")
                        var apk = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.optString("name") == "TronDroid-debug-latest.apk") apk = asset.optString("browser_download_url")
                        }
                        if (apk.isBlank()) error("The latest release does not contain the expected APK asset.")
                        ReleaseInfo(json.optString("tag_name"), json.optString("name", "Latest debug build"),
                            json.optString("body"), apk, json.optString("published_at"))
                    } finally { connection.disconnect() }
                }
            }
            result.onSuccess {
                release = it
                message = if (BuildConfig.VERSION_NAME == it.tag) "You are already using this release." else "Latest build is ready to download."
            }.onFailure { message = it.message ?: "Unable to check for updates." }
            loading = false
        }
    }

    fun downloadApk() {
        val latest = release ?: return
        runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(latest.apkUrl))
                .setTitle("TronDroid update")
                .setDescription("Downloading ${latest.name}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, "TronDroid-latest.apk")
            downloadId = manager.enqueue(request)
            downloadedUri = null
            message = "Download started. You can continue using TronDroid."
        }.onFailure { message = it.message ?: "Unable to start download." }
    }

    fun installApk() {
        val uri = downloadedUri ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            message = "Allow installs from TronDroid, return here, then tap Install."
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { message = it.message ?: "Unable to open APK installer." }
    }

    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            var status = -1
            var reason = 0
            cursor?.use { if (it.moveToFirst()) {
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            } }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadedUri = manager.getUriForDownloadedFile(id)
                    message = "APK downloaded. Install it to update TronDroid."
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    message = "Download failed (code $reason). Check your connection and try again."
                    break
                }
                else -> kotlinx.coroutines.delay(900)
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdates() }

    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TronDroid updater", style = MaterialTheme.typography.titleLarge)
                        Text("Installed version: ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { checkUpdates() }, enabled = !loading) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Check updates")
                    }
                    if (downloadedUri != null) Button(onClick = { installApk() }) { Text("Install APK") }
                    else if (release != null) OutlinedButton(onClick = { downloadApk() }) { Text("Download") }
                }
            }
        }
        release?.let { info ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    Text("Build ${info.tag}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (info.publishedAt.isNotBlank()) Text("Published: ${info.publishedAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.notes.isNotBlank()) Text(info.notes.take(3000), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Updates are downloaded from the project's GitHub rolling pre-release. Android will ask you to confirm installation.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
