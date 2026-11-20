package com.vikram.trondroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vikram.trondroid.data.BotProfile
import com.vikram.trondroid.plugins.PluginCatalog
import com.vikram.trondroid.plugins.PluginSettings
import com.vikram.trondroid.telegram.TelegramEngine
import org.drinkless.tdlib.TdApi

private enum class Tab { BOTS, PLUGINS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TronDroidHome(bots: List<BotProfile>, selected: BotProfile?, engine: TelegramEngine?,
    onSelect: (String) -> Unit, onAdd: (String, String, Int, String) -> Unit, onRemove: (String) -> Unit) {
    var tab by remember { mutableStateOf(Tab.BOTS) }
    var showAdd by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text("TronDroid", style = MaterialTheme.typography.titleLarge)
            Text("Telegram userbot manager", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, actions = { IconButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, "Add userbot") } })
    }, bottomBar = {
        NavigationBar {
            listOf(Tab.BOTS, Tab.PLUGINS, Tab.SETTINGS).forEach { t ->
                NavigationBarItem(selected = tab == t, onClick = { tab = t },
                    icon = { Icon(when(t) {
                        Tab.BOTS -> Icons.Rounded.SmartToy
                        Tab.PLUGINS -> Icons.Rounded.Extension
                        Tab.SETTINGS -> Icons.Rounded.Settings
                    }, t.name.lowercase()) }, label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
    }) { padding ->
        when(tab) {
            Tab.BOTS -> BotsScreen(Modifier.padding(padding), bots, selected, engine, onSelect, { showAdd = true }, onRemove)
            Tab.PLUGINS -> PluginsScreen(Modifier.padding(padding))
            Tab.SETTINGS -> SettingsScreen(Modifier.padding(padding))
        }
    }
    if (showAdd) AddBotDialog({ showAdd = false }) { name, phone, id, hash ->
        onAdd(name, phone, id, hash); showAdd = false
    }
}

@Composable
private fun BotsScreen(modifier: Modifier, bots: List<BotProfile>, selected: BotProfile?, engine: TelegramEngine?,
    onSelect: (String) -> Unit, onAddClick: () -> Unit, onRemove: (String) -> Unit) {
    val auth = engine?.authorization?.collectAsState()?.value
    val error = engine?.error?.collectAsState()?.value
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your accounts", style = MaterialTheme.typography.headlineSmall)
                    Text("Add and configure Telegram userbot sessions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onAddClick) { Icon(Icons.Rounded.Add, "Add") }
            }
        }
        if (bots.isEmpty()) item {
            ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.SmartToy, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("No userbots configured", style = MaterialTheme.typography.titleMedium)
                    Text("Create a profile with your Telegram API credentials to start login.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onAddClick) { Text("Add userbot") }
                }
            }
        }
        items(bots, key = { it.id }) { bot ->
            ElevatedCard(onClick = { onSelect(bot.id) }, shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor =
                    if (selected?.id == bot.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountCircle, null, Modifier.size(34.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bot.name, style = MaterialTheme.typography.titleMedium)
                        Text(bot.phoneNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selected?.id == bot.id) Icon(Icons.Rounded.CheckCircle, "Selected")
                    IconButton(onClick = { onRemove(bot.id) }) { Icon(Icons.Rounded.DeleteOutline, "Remove account") }
                }
            }
        }
        if (selected != null) item {
            Text("Session · " + selected.name, style = MaterialTheme.typography.titleLarge)
            val ready = auth?.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR
            val label = when(auth?.constructor) {
                TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> "Waiting for phone number"
                TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> "Verification code required"
                TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> "Two-step password required"
                TdApi.AuthorizationStateReady.CONSTRUCTOR -> "Connected"
                TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> "Preparing TDLib"
                else -> "Connecting"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!ready) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text(label)
            }
            if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            when(auth?.constructor) {
                TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR ->
                    AuthInput("Phone number", KeyboardType.Phone, "Continue") { engine?.submitPhone(it) }
                TdApi.AuthorizationStateWaitCode.CONSTRUCTOR ->
                    AuthInput("Telegram login code", KeyboardType.Number, "Verify code") { engine?.submitCode(it) }
                TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR ->
                    AuthInput("Two-step verification password", KeyboardType.Password, "Unlock account", true) { engine?.submitPassword(it) }
            }
            if (ready) {
                Text("Chats", style = MaterialTheme.typography.titleMedium)
                val chats = engine?.chats?.collectAsState()?.value.orEmpty()
                if (chats.isEmpty()) Text("Loading chats…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                chats.take(20).forEach { Text(it.title, Modifier.padding(vertical = 5.dp)) }
                OutlinedButton(onClick = { engine?.logout() }) { Text("Log out") }
            }
        }
    }
}

@Composable
private fun AuthInput(label: String, keyboard: KeyboardType, action: String, secret: Boolean = false, submit: (String) -> Unit) {
    var value by remember(label) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
        Button(onClick = { submit(value); value = "" }, enabled = value.isNotBlank()) { Text(action) }
    }
}

@Composable
private fun PluginsScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { PluginSettings(context) }
    var refresh by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().padding(18.dp)) {
        Text("Plugin library", style = MaterialTheme.typography.headlineSmall)
        Text("Enable only the automations you intend to run.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(PluginCatalog.all, key = { it.id }) { plugin ->
                val checked = remember(refresh, plugin.id) { settings.enabled(plugin.id) }
                ElevatedCard(shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plugin.title, style = MaterialTheme.typography.titleMedium)
                            Text(plugin.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(plugin.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked, { settings.setEnabled(plugin.id, it); refresh++ })
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        ElevatedCard(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Credential security", style = MaterialTheme.typography.titleMedium)
                Text("API hashes are encrypted with an Android Keystore AES-GCM key. TDLib session databases stay in app-private storage.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ElevatedCard(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Build variants", style = MaterialTheme.typography.titleMedium)
                Text("Debug: com.vikram.trondroid-debug\nRelease: com.vikram.trondroid", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("TronDroid 1.0.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddBotDialog(onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add Telegram userbot") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Create an app at my.telegram.org and enter its API credentials.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true)
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            OutlinedTextField(apiId, { apiId = it }, label = { Text("API ID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(apiHash, { apiHash = it }, label = { Text("API Hash") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Button(onClick = {
            val id = apiId.toIntOrNull()
            if (name.isBlank() || phone.isBlank() || id == null || apiHash.length < 20)
                error = "Enter a name, phone, numeric API ID and valid API hash."
            else onSave(name.trim(), phone.trim(), id, apiHash.trim())
        }) { Text("Save profile") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
