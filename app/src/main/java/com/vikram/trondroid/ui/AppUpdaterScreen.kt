package com.vikram.trondroid.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.core.content.ContextCompat
import com.vikram.trondroid.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private data class UpdateRelease(val name:String,val notes:String,val url:String,val version:Long,val date:String,val size:Long)
private data class Transfer(val done:Long=0,val total:Long=-1,val speed:Long=0,val eta:Long=-1,val status:Int=-1) {
    val fraction get() = if(total>0) (done.toFloat()/total).coerceIn(0f,1f) else 0f
}
private fun updateFile(c:Context)=File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"TronDroid-latest.apk")
private fun bytes(v:Long):String { if(v<0)return "—"; var n=v.toDouble(); val u=listOf("B","KB","MB","GB"); var i=0; while(n>=1024&&i<3){n/=1024;i++}; return if(i==0) "$v B" else String.format(Locale.US,"%.1f %s",n,u[i]) }
private fun duration(v:Long)=if(v<0)"Calculating…" else if(v<60)"${v}s" else "${v/60}m ${v%60}s"
@Suppress("DEPRECATION")
private fun validUpdate(c:Context,f:File,expected:Long=0):Boolean=runCatching {
    if(!f.exists()||f.length()==0L)return false
    val p=if(Build.VERSION.SDK_INT>=33)c.packageManager.getPackageArchiveInfo(f.path,PackageManager.PackageInfoFlags.of(0))
        else c.packageManager.getPackageArchiveInfo(f.path,0)
    p ?: return false
    val version=if(Build.VERSION.SDK_INT>=28)p.longVersionCode else p.versionCode.toLong()
    p.packageName==c.packageName && version>BuildConfig.VERSION_CODE && (expected==0L||version==expected)
}.getOrDefault(false)

class UpdateCleanupReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){ if(intent?.action==Intent.ACTION_MY_PACKAGE_REPLACED) updateFile(context).delete() }
}

@Composable
fun AppUpdaterScreen(modifier:Modifier=Modifier) {
    val context= LocalContext.current
    val scope= rememberCoroutineScope()
    val file= remember { updateFile(context) }
    var release by remember { mutableStateOf<UpdateRelease?>(null) }
    var message by remember { mutableStateOf("Checking for updates…") }
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf(false) }
    val downloadPrefs = remember { context.getSharedPreferences("trondroid.updater", Context.MODE_PRIVATE) }
    var id by remember { mutableStateOf(downloadPrefs.getLong("download_id", -1L).takeIf { it > 0L }) }
    var transfer by remember { mutableStateOf(Transfer()) }
    var downloading by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf(false) }

    fun discard() {
        id?.let { (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(it) }
        id=null; downloadPrefs.edit().remove("download_id").apply(); downloading=false; downloaded=false; transfer=Transfer(); file.delete()
        message="Downloaded update discarded."
    }
    fun check() {
        if(checking)return
        checking=true; checked=false; message="Checking the latest GitHub release…"
        scope.launch {
            val result=withContext(Dispatchers.IO){runCatching{
                val c=URL("https://api.github.com/repos/TronUb/TronDroid/releases/tags/debug-latest").openConnection() as HttpURLConnection
                c.connectTimeout=15000;c.readTimeout=15000;c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("User-Agent","TronDroid-Updater")
                try {
                    if(c.responseCode !in 200..299) error("GitHub returned HTTP ${c.responseCode}")
                    val j=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
                    if(j.optBoolean("draft")||!j.optBoolean("prerelease"))error("No published debug update is available.")
                    val assets=j.getJSONArray("assets");var url="";var size=0L
                    for(i in 0 until assets.length()){val a=assets.getJSONObject(i);if(a.optString("name")=="TronDroid-debug-latest.apk"){url=a.optString("browser_download_url");size=a.optLong("size")}}
                    if(url.isBlank())error("Latest release APK is missing.")
                    val notes=j.optString("body")
                    val version=notes.lineSequence().firstOrNull{it.contains("Version code:",true)}?.substringAfter(':')?.filter(Char::isDigit)?.toLongOrNull()?:0L
                    if(version<=0)error("Release metadata has no valid Android version code.")
                    UpdateRelease(j.optString("name","TronDroid update"),notes,url,version,j.optString("published_at"),size)
                } finally {c.disconnect()}
            }}
            result.onSuccess { r->
                release=r;checked=true;latest=r.version<=BuildConfig.VERSION_CODE
                if(latest){message="You're already on the latest version.";if(file.exists()&&!validUpdate(context,file))file.delete();downloaded=false}
                else {message="A new version is available.";downloaded=file.exists()&&validUpdate(context,file,r.version);if(!downloaded&&file.exists())file.delete();if(downloaded)message="Update already downloaded and ready to install."}
            }.onFailure { message=it.message?: "Update check failed." }
            checking=false
        }
    }
    fun startDownload(){
        val r=release?:return
        runCatching {
            file.delete()
            val manager=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request=DownloadManager.Request(Uri.parse(r.url)).setTitle("TronDroid update").setDescription("Downloading ${r.name}")
                .setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,file.name)
            id=manager.enqueue(request);downloadPrefs.edit().putLong("download_id", id!!).apply();transfer=Transfer(total=r.size.takeIf{it>0}?:-1);downloading=true;downloaded=false;message="Downloading update…"
        }.onFailure{message=it.message?:"Could not start download."}
    }
    fun install(){
        val r=release
        if(!validUpdate(context,file,r?.version?:0)){file.delete();downloaded=false;message="APK is invalid or incomplete. Download it again.";return}
        if(Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls()){
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            message="Allow installs from TronDroid, return here, then tap Install.";return
        }
        runCatching {
            val uri=id?.let{(context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(it)}?: error("Android download record is unavailable. Discard this APK and download it again.")
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            message="Android installer opened. The APK will be removed after the update."
        }.onFailure{message=it.message?:"Unable to open installer."}
    }

    LaunchedEffect(id) {
        val key=id?:return@LaunchedEffect
        val manager=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var old=0L;var time=System.currentTimeMillis()
        while(true){
            val cursor=manager.query(DownloadManager.Query().setFilterById(key))
            var status=-1;var reason=0;var done=0L;var total=-1L
            cursor?.use{if(it.moveToFirst()){status=it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));reason=it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));done=it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));total=it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))}}
            val now=System.currentTimeMillis();val speed=((done-old).coerceAtLeast(0)*1000)/maxOf(1,now-time)
            transfer=Transfer(done,total,speed,if(speed>0&&total>0)(total-done).coerceAtLeast(0)/speed else -1,status)
            old=done;time=now
            when(status){
                DownloadManager.STATUS_SUCCESSFUL->{
                    val ok=withContext(Dispatchers.IO){validUpdate(context,file,release?.version?:0)}
                    downloading=false;downloaded=ok
                    if(!ok)downloadPrefs.edit().remove("download_id").apply()
                    if(ok)message="Download complete. Install or discard the APK."
                    else{file.delete();message="Downloaded APK failed validation and was deleted."}
                    break
                }
                DownloadManager.STATUS_FAILED->{manager.remove(key);downloadPrefs.edit().remove("download_id").apply();file.delete();downloading=false;downloaded=false;message="Download failed (code $reason). Incomplete APK deleted.";break}
                else->delay(800)
            }
        }
    }
    DisposableEffect(context){
        val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){if(i?.action==Intent.ACTION_MY_PACKAGE_REPLACED){file.delete();downloadPrefs.edit().remove("download_id").apply();downloaded=false;downloading=false}}}
        ContextCompat.registerReceiver(context,receiver,android.content.IntentFilter(Intent.ACTION_MY_PACKAGE_REPLACED),ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose{runCatching{context.unregisterReceiver(receiver)}}
    }
    LaunchedEffect(Unit){if(file.exists()&&!validUpdate(context,file))file.delete();check()}

    Column(modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Card(shape=RoundedCornerShape(22.dp)){
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Icon(Icons.Rounded.SystemUpdate,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(32.dp));Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)){Text("TronDroid updater",style=MaterialTheme.typography.titleLarge);Text("Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                Text(message,style=MaterialTheme.typography.bodyMedium)
                if(checking)LinearProgressIndicator(Modifier.fillMaxWidth())
                else if(checked&&latest)Button(onClick={check()},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Refresh,null);Spacer(Modifier.width(8.dp));Text("Check updates")}
                else if(release!=null&&!latest){
                    Text(release!!.name,style=MaterialTheme.typography.titleMedium)
                    Text("Version code ${release!!.version} · ${release!!.date.take(10)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(downloading){
                        LinearProgressIndicator(progress={transfer.fraction},modifier=Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${(transfer.fraction*100).toInt()}%");Text("${bytes(transfer.done)} / ${bytes(transfer.total)}",style=MaterialTheme.typography.bodySmall)}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Column{Text("Speed",style=MaterialTheme.typography.labelSmall);Text("${bytes(transfer.speed)}/s")}
                            Column(horizontalAlignment=Alignment.CenterHorizontally){Text("Left",style=MaterialTheme.typography.labelSmall);Text(bytes((transfer.total-transfer.done).coerceAtLeast(0)))}
                            Column(horizontalAlignment=Alignment.End){Text("Time left",style=MaterialTheme.typography.labelSmall);Text(duration(transfer.eta))}
                        }
                        OutlinedButton(onClick={discard()},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Close,null);Spacer(Modifier.width(8.dp));Text("Cancel download")}
                    }else if(downloaded){
                        Text("APK ready · ${bytes(file.length())}",color=MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            OutlinedButton(onClick={discard()},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.DeleteOutline,null);Spacer(Modifier.width(5.dp));Text("Discard")}
                            Button(onClick={install()},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.InstallMobile,null);Spacer(Modifier.width(5.dp));Text("Install APK")}
                        }
                    }else Button(onClick={startDownload()},modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Download,null);Spacer(Modifier.width(8.dp));Text("Download update")}
                }else if(!checking)OutlinedButton(onClick={check()},modifier=Modifier.fillMaxWidth()){Text("Retry update check")}
            }
        }
        release?.takeIf{!latest}?.let{Card(shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp)){Text("What's new",style=MaterialTheme.typography.titleMedium);Text(it.notes.take(3000),style=MaterialTheme.typography.bodySmall)}}}
        Text("APK package and version are checked before installation. Android will ask you to confirm the update.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
