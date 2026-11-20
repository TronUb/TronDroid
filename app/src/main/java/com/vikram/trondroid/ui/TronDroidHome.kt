package com.vikram.trondroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vikram.trondroid.data.BotProfile
import com.vikram.trondroid.plugins.PluginCatalog
import com.vikram.trondroid.plugins.PluginSettings
import com.vikram.trondroid.telegram.TelegramEngine
import org.drinkless.tdlib.TdApi

private enum class Tab { USERBOTS, PLUGINS, SETTINGS }
private sealed interface Page {
    data object Home : Page
    data class Userbot(val id: String) : Page
    data object Credentials : Page
    data class CredentialDetail(val id: String) : Page
    data object GeneralSettings : Page
    data object Updater : Page
    data class PluginDetail(val id: String) : Page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TronDroidHome(
    bots: List<BotProfile>, selected: BotProfile?, engine: TelegramEngine?,
    onSelect: (String) -> Unit, onAdd: (String, String, Int, String) -> Unit,
    onRemove: (String) -> Unit, onReadApiHash: (String) -> String?,
    onUpdateApiHash: (String, String) -> Unit,
    themeMode: String, onThemeChange: (String) -> Unit
) {
    var tab by remember { mutableStateOf(Tab.USERBOTS) }
    var pageStack by remember { mutableStateOf(listOf<Page>(Page.Home)) }
    var navigationDirection by remember { mutableIntStateOf(1) }
    val page = pageStack.last()
    fun navigate(next: Page) { navigationDirection = 1; pageStack = pageStack + next }
    fun goBack() { if (pageStack.size > 1) { navigationDirection = -1; pageStack = pageStack.dropLast(1) } }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BotProfile?>(null) }
    var pendingLogout by remember { mutableStateOf<BotProfile?>(null) }
    val isHome = page == Page.Home
    val title = when (val p = page) {
        Page.Home -> when (tab) { Tab.USERBOTS -> "Userbots"; Tab.PLUGINS -> "Plugins"; Tab.SETTINGS -> "Settings" }
        is Page.Userbot -> bots.firstOrNull { it.id == p.id }?.name ?: "Userbot"
        Page.Credentials -> "Credentials"
        is Page.CredentialDetail -> bots.firstOrNull { it.id == p.id }?.name ?: "Credentials"
        Page.GeneralSettings -> "General"
        Page.Updater -> "App updates"
        is Page.PluginDetail -> PluginCatalog.all.firstOrNull { it.id == p.id }?.title ?: "Plugin"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (!isHome) IconButton(onClick = {
                        goBack()
                    }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                }
            )
        },
        bottomBar = {
            if (isHome) NavigationBar {
                listOf(Tab.USERBOTS, Tab.PLUGINS, Tab.SETTINGS).forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t },
                        icon = { Icon(when (t) {
                            Tab.USERBOTS -> Icons.Rounded.SmartToy
                            Tab.PLUGINS -> Icons.Rounded.Extension
                            Tab.SETTINGS -> Icons.Rounded.Settings
                        }, t.name.lowercase()) },
                        label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
        },
        floatingActionButton = {
            if (isHome && tab == Tab.USERBOTS) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Rounded.Add, "Add userbot")
                }
            }
        }
    ) { padding ->
        AnimatedContent(targetState = page, transitionSpec = {
            val direction = navigationDirection
            (slideInHorizontally { width -> if (direction > 0) width else -width } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> if (direction > 0) -width else width } + fadeOut())
        }, label = "page-slide") { currentPage ->
        when (val current = currentPage) {
            Page.Home -> when (tab) {
                Tab.USERBOTS -> UserbotsScreen(Modifier.padding(padding), bots, onOpen = {
                    onSelect(it); navigate(Page.Userbot(it))
                }, onDelete = { pendingDelete = it })
                Tab.PLUGINS -> PluginsScreen(Modifier.padding(padding), onOpen = { navigate(Page.PluginDetail(it)) })
                Tab.SETTINGS -> SettingsScreen(Modifier.padding(padding),
                    onCredentials = { navigate(Page.Credentials) }, onGeneral = { navigate(Page.GeneralSettings) }, onUpdater = { navigate(Page.Updater) }, themeMode = themeMode, onThemeChange = onThemeChange)
            }
            is Page.Userbot -> {
                val bot = bots.firstOrNull { it.id == current.id }
                UserbotDetailScreen(Modifier.padding(padding), bot,
                    engine = if (selected?.id == current.id) engine else null,
                    onDelete = { if (bot != null) pendingDelete = bot }, onLogout = { if (bot != null) pendingLogout = bot })
            }
            Page.Credentials -> CredentialsScreen(Modifier.padding(padding), bots, onOpen = { navigate(Page.CredentialDetail(it)) })
            is Page.CredentialDetail -> {
                val bot = bots.firstOrNull { it.id == current.id }
                if (bot != null) CredentialDetailScreen(Modifier.padding(padding), bot,
                    apiHash = onReadApiHash(bot.id).orEmpty(),
                    onSaveApiHash = { onUpdateApiHash(bot.id, it) })
            }
            Page.GeneralSettings -> GeneralSettingsScreen(Modifier.padding(padding))
            Page.Updater -> AppUpdaterScreen(Modifier.padding(padding))
            is Page.PluginDetail -> PluginDetailScreen(Modifier.padding(padding), current.id)
        } }
    }
    BackHandler(enabled = pageStack.size > 1 || tab != Tab.USERBOTS) { if (pageStack.size > 1) goBack() else tab = Tab.USERBOTS }
    if (showAdd) AddBotDialog(onDismiss = { showAdd = false }) { name, phone, id, hash ->
        onAdd(name, phone, id, hash); showAdd = false
    }
    pendingLogout?.let { bot ->
        AlertDialog(onDismissRequest = { pendingLogout = null }, title = { Text("Log out of ${bot.name}?") },
            text = { Text("This will end the Telegram session on this device. You can sign in again later.") },
            confirmButton = { TextButton(onClick = { if (selected?.id == bot.id) engine?.logout(); pendingLogout = null }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { pendingLogout = null }) { Text("Cancel") } })
    }
    pendingDelete?.let { bot ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete userbot?") },
            text = { Text("Remove ${bot.name} and its saved API hash from TronDroid? The local Telegram session folder will also be deleted.") },
            confirmButton = {
                TextButton(onClick = { onRemove(bot.id); pendingDelete = null; pageStack = listOf(Page.Home) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun UserbotsScreen(modifier: Modifier, bots: List<BotProfile>, onOpen: (String) -> Unit, onDelete: (BotProfile) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (bots.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(top = 90.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.SmartToy, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("No userbots yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap + to add a Telegram account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(bots, key = { it.id }) { bot ->
            ElevatedCard(onClick = { onOpen(bot.id) }, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountCircle, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bot.name, style = MaterialTheme.typography.titleMedium)
                        Text(bot.phoneNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(bot) }) { Icon(Icons.Rounded.DeleteOutline, "Delete userbot") }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UserbotDetailScreen(modifier: Modifier, bot: BotProfile?, engine: TelegramEngine?, onDelete: () -> Unit, onLogout: () -> Unit) {
    if (bot == null) return
    val auth = engine?.authorization?.collectAsState()?.value
    val logs = engine?.logs?.collectAsState()?.value.orEmpty()
    val chats = engine?.chats?.collectAsState()?.value.orEmpty()
    val ready = auth?.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR
    var logsFullscreen by remember(bot.id) { mutableStateOf(false) }
    val terminal = androidx.compose.ui.graphics.Color(0xFF050705)
    val terminalText = androidx.compose.ui.graphics.Color(0xFFB8D8C0)
    val terminalAccent = androidx.compose.ui.graphics.Color(0xFF9BE7B0)

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AccountCircle, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(bot.name, style = MaterialTheme.typography.titleLarge)
                            Text(bot.phoneNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    AssistChip(onClick = {}, label = { Text(if (ready) "Connected" else authLabel(auth)) },
                        leadingIcon = { Icon(if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Sync, null) })
                    when (auth?.constructor) {
                        TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> AuthInput("Phone number", KeyboardType.Phone, "Continue") { engine?.submitPhone(it) }
                        TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> AuthInput("Telegram login code", KeyboardType.Number, "Verify code") { engine?.submitCode(it) }
                        TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> AuthInput("Two-step verification password", KeyboardType.Password, "Unlock account", true) { engine?.submitPassword(it) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onLogout, modifier = Modifier.weight(1f)) { Text("Log out") }
                        Button(onClick = onDelete, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError)) {
                            Icon(Icons.Rounded.DeleteOutline, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Delete userbot")
                        }
                    }
                }
            }
        }
        item {
            Text("Live logs", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp).padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = terminal), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Code, null, tint = terminalAccent)
                        Spacer(Modifier.width(8.dp))
                        Text("TRONDROID / SESSION", color = terminalAccent, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text("LIVE", color = terminalAccent, style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { logsFullscreen = true }) {
                            Icon(Icons.Rounded.Fullscreen, "Expand logs", tint = terminalAccent)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = androidx.compose.ui.graphics.Color(0xFF26352A))
                    if (logs.isEmpty()) Text("> waiting for events…", color = terminalText)
                    else Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        logs.takeLast(100).forEach { entry ->
                            Text("[${entry.time}] ${if (entry.isError) "ERROR" else "INFO"}  ${entry.message}",
                                color = if (entry.isError) androidx.compose.ui.graphics.Color(0xFFFF8A80) else terminalText,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                }
            }
        }
        item {
            val users = chats.count { it.type is TdApi.ChatTypePrivate }
            val groups = chats.count { it.type is TdApi.ChatTypeBasicGroup }
            val supergroups = chats.count { it.type is TdApi.ChatTypeSupergroup && !(it.type as TdApi.ChatTypeSupergroup).isChannel }
            val channels = chats.count { it.type is TdApi.ChatTypeSupergroup && (it.type as TdApi.ChatTypeSupergroup).isChannel }
            val stats = listOf("All chats" to chats.size, "Users" to users, "Groups" to groups, "Supergroups" to supergroups, "Channels" to channels)
            Text("Account insights", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.chunked(2).forEach { rowStats ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowStats.forEach { (label, count) ->
                            ElevatedCard(Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Text(count.toString(), style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(label, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (rowStats.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { Text("Recent chats", style = MaterialTheme.typography.titleMedium) }
        if (!ready) item { Text("Connect this account to load recent chats.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else if (chats.isEmpty()) item { Text("No recent chats have been loaded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else items(chats.take(20)) { chat ->
            ListItem(headlineContent = { Text(chat.title, maxLines = 1) },
                leadingContent = { Icon(Icons.Rounded.ChatBubbleOutline, null) })
        }
    }

    if (logsFullscreen) {
        Dialog(onDismissRequest = { logsFullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = terminal) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Code, null, tint = terminalAccent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("TRONDROID / SESSION", color = terminalAccent, style = MaterialTheme.typography.titleMedium)
                            Text("LIVE LOG STREAM · ${logs.size} EVENTS", color = terminalText, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { logsFullscreen = false }) {
                            Icon(Icons.Rounded.FullscreenExit, "Exit fullscreen", tint = terminalAccent)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = androidx.compose.ui.graphics.Color(0xFF26352A))
                    if (logs.isEmpty()) Text("> waiting for events…", color = terminalText)
                    else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(logs.takeLast(300)) { entry ->
                            Text("[${entry.time}] ${if (entry.isError) "ERROR" else "INFO"}  ${entry.message}",
                                color = if (entry.isError) androidx.compose.ui.graphics.Color(0xFFFF8A80) else terminalText,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun authLabel(auth: TdApi.AuthorizationState?): String = when (auth?.constructor) {
    TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> "Phone number required"
    TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> "Verification code required"
    TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> "Two-step password required"
    TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> "Preparing session"
    TdApi.AuthorizationStateReady.CONSTRUCTOR -> "Connected"
    else -> "Connecting"
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

private data class Country(val name: String, val dial: String)
private val countries = listOf(
    Country("India", "+91"), Country("United States", "+1"), Country("United Kingdom", "+44"),
    Country("United Arab Emirates", "+971"), Country("Canada", "+1"), Country("Australia", "+61"),
    Country("Germany", "+49"), Country("France", "+33"), Country("Japan", "+81"),
    Country("Nepal", "+977"), Country("Bangladesh", "+880"), Country("Pakistan", "+92"),
    Country("Sri Lanka", "+94"), Country("Singapore", "+65"), Country("Brazil", "+55"),
    Country("Russia", "+7"), Country("Ukraine", "+380"), Country("South Africa", "+27")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBotDialog(onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(countries.first()) }
    var countryMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add userbot") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(expanded = countryMenu, onExpandedChange = { countryMenu = it }, modifier = Modifier.width(124.dp)) {
                    OutlinedTextField(value = country.dial, onValueChange = {}, readOnly = true,
                        label = { Text("Code") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(countryMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true)
                    ExposedDropdownMenu(expanded = countryMenu, onDismissRequest = { countryMenu = false }) {
                        countries.forEach { c -> DropdownMenuItem(text = { Text("${c.name}  ${c.dial}") },
                            onClick = { country = c; countryMenu = false }) }
                    }
                }
                OutlinedTextField(phone, { phone = it.filter(Char::isDigit) }, label = { Text("Phone number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(apiId, { apiId = it }, label = { Text("Telegram API ID") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiHash, { apiHash = it }, label = { Text("Telegram API Hash") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Button(onClick = {
            val id = apiId.toIntOrNull()
            if (name.isBlank() || phone.length < 5 || id == null || apiHash.length < 20)
                error = "Enter a name, valid phone number, API ID and API hash."
            else onSave(name.trim(), country.dial + phone, id, apiHash.trim())
        }) { Text("Save userbot") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun PluginsScreen(modifier: Modifier, onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { PluginSettings(context) }
    var refresh by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text("Command handler", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        var handler by remember { mutableStateOf(settings.handler()) }
        OutlinedTextField(handler, { handler = it.take(8); settings.setHandler(it.take(8)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Command prefix") },
            supportingText = { Text("Example: .ban, .mute, .afk") }, singleLine = true)
        Text("Plugin library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(PluginCatalog.all, key = { it.id }) { plugin ->
                val checked = remember(refresh, plugin.id) { settings.enabled(plugin.id) }
                ElevatedCard(onClick = { onOpen(plugin.id) }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plugin.title, style = MaterialTheme.typography.titleMedium)
                            Text("${plugin.commands.size} commands · ${plugin.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked, { settings.setEnabled(plugin.id, it); refresh++ })
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginDetailScreen(modifier: Modifier, id: String) {
    val plugin = PluginCatalog.all.firstOrNull { it.id == id } ?: return
    val handler = PluginSettings(androidx.compose.ui.platform.LocalContext.current).handler()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text(plugin.summary, style = MaterialTheme.typography.bodyLarge)
            Text("Commands", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        }
        items(plugin.commands) { command ->
            ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(handler + command.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(command.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Text("Usage: " + handler + command.usage, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(modifier: Modifier, onCredentials: () -> Unit, onGeneral: () -> Unit, onUpdater: () -> Unit, themeMode: String, onThemeChange: (String) -> Unit) {
    Column(modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
        Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        ElevatedCard(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system", "light", "dark").forEachIndexed { index, mode ->
                        SegmentedButton(selected = themeMode == mode, onClick = { onThemeChange(mode) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                            Text(mode.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        }
        SettingsGroup("General", listOf(Triple("General settings", "Appearance and app behaviour", Icons.Rounded.Tune)), listOf(onGeneral))
        SettingsGroup("Account", listOf(Triple("Credentials", "API keys, account details and local sessions", Icons.Rounded.Key)), listOf(onCredentials))
        SettingsGroup("Application", listOf(Triple("App updates", "Check and install the latest APK", Icons.Rounded.SystemUpdate)), listOf(onUpdater))
        Text("Security", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp))
        ElevatedCard(shape = RoundedCornerShape(20.dp)) {
            ListItem(headlineContent = { Text("Local credential storage") },
                supportingContent = { Text("API hashes are encrypted with Android Keystore") },
                leadingContent = { Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary) })
        }
        Text("TronDroid 1.0.0 · Telegram session manager", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 22.dp))
    }
}

@Composable
private fun SettingsGroup(title: String, entries: List<Triple<String, String, androidx.compose.ui.graphics.vector.ImageVector>>, actions: List<() -> Unit>) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .then(Modifier).padding(horizontal = 16.dp, vertical = 10.dp)
                    .clickable { actions[index]() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(entry.third, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.first, style = MaterialTheme.typography.titleSmall)
                    Text(entry.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun GeneralSettingsScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("General preferences", style = MaterialTheme.typography.titleMedium)
        Text("Theme, account and update preferences are available directly in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Build variants: debug com.vikram.trondroid.debug · release com.vikram.trondroid",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CredentialsScreen(modifier: Modifier, bots: List<BotProfile>, onOpen: (String) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Userbot credentials", style = MaterialTheme.typography.titleMedium)
            Text("Choose an account to inspect its saved keys and generated session information.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(bots, key = { it.id }) { bot ->
            ElevatedCard(onClick = { onOpen(bot.id) }, shape = RoundedCornerShape(18.dp)) {
                ListItem(headlineContent = { Text(bot.name) }, supportingContent = { Text(bot.phoneNumber) },
                    leadingContent = { Icon(Icons.Rounded.Key, null) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) })
            }
        }
        if (bots.isEmpty()) item { Text("Add a userbot first to manage its credentials.") }
    }
}

@Composable
private fun CredentialDetailScreen(modifier: Modifier, bot: BotProfile, apiHash: String, onSaveApiHash: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var hash by remember(bot.id, apiHash) { mutableStateOf(apiHash) }
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Saved credentials", style = MaterialTheme.typography.titleMedium)
            Text("Sensitive values are stored locally. Session data is generated by TDLib and cannot be edited here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { CredentialField("Profile name", bot.name) }
        item { CredentialField("Phone number", bot.phoneNumber) }
        item { CredentialField("Telegram API ID", bot.apiId.toString()) }
        item {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Telegram API Hash", style = MaterialTheme.typography.labelLarge)
                    if (editing) OutlinedTextField(hash, { hash = it }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true)
                    else Text(if (reveal) apiHash else "••••••••••••••••", modifier = Modifier.padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = !editing; hash = apiHash }) { Text(if (editing) "Cancel" else "Edit") }
                        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                        if (editing) Button(onClick = { onSaveApiHash(hash); editing = false }) { Text("Save") }
                        IconButton(onClick = { android.content.ClipData.newPlainText("Telegram API Hash", apiHash).also {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(it)
                        } }) { Icon(Icons.Rounded.ContentCopy, "Copy API hash") }
                    }
                }
            }
        }
        item { CredentialField("TDLib session", context.filesDir.resolve("tdlib/" + bot.id).absolutePath) }
    }
}

@Composable
private fun CredentialField(label: String, value: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            }) { Icon(Icons.Rounded.ContentCopy, "Copy $label") }
        }
    }
}
