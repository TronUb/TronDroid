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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.input.pointer.pointerInput
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
import com.vikram.trondroid.telegram.TelegramBotRuntime
import com.vikram.trondroid.plugins.PluginOwner
import org.drinkless.tdlib.TdApi

private enum class Tab { USERBOTS, PLUGINS, SETTINGS }
private sealed interface Page {
    data object Home : Page
    data class Userbot(val id: String) : Page
    data object GeneralSettings : Page
    data object About : Page
    data object Updater : Page
    data class PluginDetail(val id: String) : Page
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TronDroidHome(
    bots: List<BotProfile>, selected: BotProfile?, engine: TelegramEngine?,
    onSelect: (String) -> Unit, onRename: (String, String) -> Unit, onAdd: (String, String, Int, String) -> Unit,
    onRemove: (String) -> Unit, onToggle: (String, Boolean) -> Unit, onReadApiHash: (String) -> String?,
    onUpdateApiHash: (String, String) -> Unit,
    onReadCustomCredentials: (String) -> Map<String, String>,
    onSaveCustomCredentials: (String, Map<String, String>) -> Unit,
    onBotToggle: (String, Boolean) -> Unit,
    themeMode: String, onThemeChange: (String) -> Unit,
    dynamicColors: Boolean, onDynamicColorsChange: (Boolean) -> Unit
) {
    var tab by remember { mutableStateOf(Tab.USERBOTS) }
    var pageStack by remember { mutableStateOf(listOf<Page>(Page.Home)) }
    var navigationDirection by remember { mutableIntStateOf(1) }
    val page = pageStack.last()
    fun navigate(next: Page) { navigationDirection = 1; pageStack = pageStack + next }
    fun goBack() { if (pageStack.size > 1) { navigationDirection = -1; pageStack = pageStack.dropLast(1) } }
    var showAdd by remember { mutableStateOf(false) }
    var pluginOwner by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingDelete by remember { mutableStateOf<BotProfile?>(null) }
    var pendingLogout by remember { mutableStateOf<BotProfile?>(null) }
    val isHome = page == Page.Home
    val title = when (val p = page) {
        Page.Home -> when (tab) { Tab.USERBOTS -> "Userbots"; Tab.PLUGINS -> "Plugins"; Tab.SETTINGS -> "Settings" }
        is Page.Userbot -> bots.firstOrNull { it.id == p.id }?.name ?: "Userbot"
        Page.GeneralSettings -> "General"
        Page.About -> "About TronDroid"
        Page.Updater -> "App updates"
        is Page.PluginDetail -> PluginCatalog.find(p.id)?.title ?: "Plugin"
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
            } else if (isHome && tab == Tab.PLUGINS) {
                FloatingActionButton(onClick = {
                    android.widget.Toast.makeText(
                        context,
                        "Executable external plugins are disabled for security. Built-in plugins are available here; .tdplugin files are metadata-only.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }) {
                    Icon(Icons.Rounded.Info, "Plugin security information")
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
                }, onToggle = { id, enabled -> onToggle(id, enabled) })
                Tab.PLUGINS -> PluginsScreen(Modifier.padding(padding), selectedOwner = pluginOwner, onOwnerChange = { pluginOwner = it }, onOpen = { navigate(Page.PluginDetail(it)) })
                Tab.SETTINGS -> SettingsScreen(Modifier.padding(padding),
                    onGeneral = { navigate(Page.GeneralSettings) }, onAbout = { navigate(Page.About) }, onUpdater = { navigate(Page.Updater) }, themeMode = themeMode, onThemeChange = onThemeChange,
                    dynamicColors = dynamicColors, onDynamicColorsChange = onDynamicColorsChange)
            }
            is Page.Userbot -> {
                val bot = bots.firstOrNull { it.id == current.id }
                UserbotDetailScreen(
                    Modifier.padding(padding),
                    bot,
                    engine = if (selected?.id == current.id) engine else null,
                    apiHash = bot?.let { onReadApiHash(it.id) }.orEmpty(),
                    onSaveApiHash = { value -> bot?.let { onUpdateApiHash(it.id, value) } },
                    customCredentials = bot?.let { onReadCustomCredentials(it.id) }.orEmpty(),
                    onSaveCustomCredentials = { values -> bot?.let { onSaveCustomCredentials(it.id, values) } },
                    onDelete = { if (bot != null) pendingDelete = bot },
                    onLogout = { if (bot != null) pendingLogout = bot },
                    onRename = { name -> bot?.let { onRename(it.id, name) } },
                    onToggle = onToggle,
                    onBotToggle = onBotToggle
                )
            }
            Page.GeneralSettings -> GeneralSettingsScreen(Modifier.padding(padding))
            Page.About -> AboutScreen(Modifier.padding(padding))
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
private fun UserbotsScreen(modifier: Modifier, bots: List<BotProfile>, onOpen: (String) -> Unit, onToggle: (String, Boolean) -> Unit) {
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
            Card(onClick = { onOpen(bot.id) }, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountCircle, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bot.name, style = MaterialTheme.typography.titleMedium)
                        Text(bot.phoneNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = bot.enabled, onCheckedChange = { onToggle(bot.id, it) })
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private enum class UserbotSection(val label: String) {
    GENERAL("General"), LOGS("Logs"), CREDENTIALS("Credentials"), INSIGHTS("Insights"), SETTINGS("Settings")
}

@Composable
private fun UserbotDetailScreen(
    modifier: Modifier,
    bot: BotProfile?,
    engine: TelegramEngine?,
    apiHash: String,
    onSaveApiHash: (String) -> Unit,
    customCredentials: Map<String, String>,
    onSaveCustomCredentials: (Map<String, String>) -> Unit,
    onDelete: () -> Unit,
    onLogout: () -> Unit,
    onRename: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onBotToggle: (String, Boolean) -> Unit
) {
    if (bot == null) return
    val auth = engine?.authorization?.collectAsState()?.value
    val logs = engine?.logs?.collectAsState()?.value.orEmpty()
    val chats = engine?.chats?.collectAsState()?.value.orEmpty()
    val account = engine?.account?.collectAsState()?.value
    val accountAvatar = remember(account?.photoPath) { account?.photoPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    val botAvatar = remember(bot.botPhotoPath) { bot.botPhotoPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    val ready = auth?.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR
    var section by remember(bot.id) { mutableStateOf(UserbotSection.GENERAL) }
    var recentChatsExpanded by remember(bot.id) { mutableStateOf(false) }
    var showRenameDialog by remember(bot.id) { mutableStateOf(false) }
    var renamedProfile by remember(bot.id, bot.name) { mutableStateOf(bot.name) }
    val terminal = androidx.compose.ui.graphics.Color(0xFF050705)
    val terminalText = androidx.compose.ui.graphics.Color(0xFFB8D8C0)
    val terminalAccent = androidx.compose.ui.graphics.Color(0xFF9BE7B0)

    Column(modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = UserbotSection.entries.indexOf(section),
            edgePadding = 16.dp,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)) }
        ) {
            UserbotSection.entries.forEach { item ->
                Tab(
                    selected = section == item,
                    onClick = { section = item },
                    text = { Text(item.label, maxLines = 1) },
                    icon = {
                        Icon(
                            when (item) {
                                UserbotSection.GENERAL -> Icons.Rounded.Tune
                                UserbotSection.LOGS -> Icons.Rounded.Terminal
                                UserbotSection.CREDENTIALS -> Icons.Rounded.Key
                                UserbotSection.INSIGHTS -> Icons.Rounded.Insights
                                UserbotSection.SETTINGS -> Icons.Rounded.Settings
                            },
                            contentDescription = null
                        )
                    }
                )
            }
        }

        Box(
            Modifier.fillMaxSize().pointerInput(section) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val index = UserbotSection.entries.indexOf(section)
                        if (dragDistance < -72f && index < UserbotSection.entries.lastIndex) {
                            section = UserbotSection.entries[index + 1]
                        } else if (dragDistance > 72f && index > 0) {
                            section = UserbotSection.entries[index - 1]
                        }
                        dragDistance = 0f
                    },
                    onHorizontalDrag = { _, amount -> dragDistance += amount }
                )
            }
        ) {
        when (section) {
            UserbotSection.GENERAL -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 18.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (accountAvatar != null) {
                                    androidx.compose.foundation.Image(bitmap = accountAvatar, contentDescription = "Telegram profile photo",
                                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)))
                                } else {
                                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(56.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.Person, contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(account?.name?.ifBlank { bot.name } ?: bot.name, style = MaterialTheme.typography.titleLarge)
                                    Text(account?.username?.ifBlank { bot.phoneNumber } ?: bot.phoneNumber,
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (bot.enabled && ready) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (bot.enabled && ready) Icons.Rounded.CheckCircle else Icons.Rounded.Circle,
                                            null,
                                            Modifier.size(12.dp),
                                            tint = if (bot.enabled && ready) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (!bot.enabled) "Disabled" else if (ready) "Connected" else authLabel(auth),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            if (account != null) {
                                Column(Modifier.fillMaxWidth().padding(start = 70.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Telegram ID  " + account.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Phone  " + account.phone.ifBlank { bot.phoneNumber }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Data center  Managed by TDLib", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (account.isPremium || account.isVerified) {
                                        Text(listOfNotNull(if (account.isPremium) "Premium" else null, if (account.isVerified) "Verified" else null).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Userbot", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (bot.enabled) "Running in the background" else "Paused · no background activity",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(checked = bot.enabled, onCheckedChange = { onToggle(bot.id, it) })
                            }
                            if (bot.enabled) when (auth?.constructor) {
                                TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR ->
                                    AuthInput("Phone number", KeyboardType.Phone, "Continue") { engine?.submitPhone(it) }
                                TdApi.AuthorizationStateWaitCode.CONSTRUCTOR ->
                                    AuthInput("Telegram login code", KeyboardType.Number, "Verify code") { engine?.submitCode(it) }
                                TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR ->
                                    AuthInput("Two-step verification password", KeyboardType.Password, "Unlock account", true) { engine?.submitPassword(it) }
                            }
                            OutlinedButton(
                                onClick = onLogout,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.Logout, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Log out")
                            }
                        }
                    }
                }

                item {
                    if (customCredentials.entries.any { it.key.equals("BOT_TOKEN", ignoreCase = true) && it.value.isNotBlank() }) {
                        Card(shape = RoundedCornerShape(22.dp)) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (botAvatar != null) {
                                    androidx.compose.foundation.Image(bitmap = botAvatar, contentDescription = "Bot profile photo",
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)))
                                } else {
                                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(48.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Text(bot.botName ?: "Telegram bot", style = MaterialTheme.typography.titleMedium)
                                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                            Text("BOT", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        }
                                    }
                                    Text(if (!bot.botEnabled) "Bot session paused" else if (bot.botName != null) "Connected · running in the background" else "Connecting to Telegram Bot API",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    bot.botUsername?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    bot.botTelegramId?.let { Text("ID · $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Switch(checked = bot.botEnabled, onCheckedChange = { onBotToggle(bot.id, it) })
                            }
                        }
                    }
                }
            }

            UserbotSection.LOGS -> Card(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = terminal),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Code, null, tint = terminalAccent)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("TRONDROID / SESSION", color = terminalAccent, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "LIVE LOG STREAM · ${logs.size} EVENTS",
                                color = terminalText.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = androidx.compose.ui.graphics.Color(0xFF14251A)
                        ) {
                            Text("LIVE", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = terminalAccent, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(
                        Modifier.padding(vertical = 12.dp),
                        color = androidx.compose.ui.graphics.Color(0xFF26352A)
                    )
                    if (logs.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("> waiting for events…", color = terminalText)
                        }
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 18.dp)
                        ) {
                            items(logs.takeLast(500)) { entry ->
                                Text(
                                    "[${entry.time}] ${if (entry.isError) "ERROR" else "INFO"}  ${entry.message}",
                                    color = if (entry.isError) androidx.compose.ui.graphics.Color(0xFFFF8A80) else terminalText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            UserbotSection.CREDENTIALS -> CredentialDetailScreen(
                Modifier.fillMaxSize(),
                bot = bot,
                apiHash = apiHash,
                onSaveApiHash = onSaveApiHash,
                customCredentials = customCredentials,
                onSaveCustomCredentials = onSaveCustomCredentials
            )

            UserbotSection.INSIGHTS -> {
                val users = chats.count { it.type is TdApi.ChatTypePrivate }
                val groups = chats.count { it.type is TdApi.ChatTypeBasicGroup }
                val supergroups = chats.count {
                    it.type is TdApi.ChatTypeSupergroup && !(it.type as TdApi.ChatTypeSupergroup).isChannel
                }
                val channels = chats.count {
                    it.type is TdApi.ChatTypeSupergroup && (it.type as TdApi.ChatTypeSupergroup).isChannel
                }
                val stats = listOf(
                    "All chats" to chats.size,
                    "Users" to users,
                    "Groups" to groups,
                    "Supergroups" to supergroups,
                    "Channels" to channels
                )
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Account insights", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A breakdown of chats available to this Telegram account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(stats.chunked(2)) { rowStats ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowStats.forEach { (label, count) ->
                                Card(
                                    Modifier.weight(1f),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text(
                                            count.toString(),
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(label, style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (rowStats.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                ListItem(
                                    headlineContent = { Text("Userbot connection") },
                                    supportingContent = { Text(if (!bot.enabled) "Disabled" else authLabel(auth)) },
                                    leadingContent = { Icon(Icons.Rounded.AccountCircle, null, tint = MaterialTheme.colorScheme.primary) }
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                ListItem(
                                    headlineContent = { Text("Live log events") },
                                    supportingContent = { Text("${logs.size} events currently retained in this session") },
                                    leadingContent = { Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary) }
                                )
                                if (customCredentials.entries.any { it.key.equals("BOT_TOKEN", ignoreCase = true) && it.value.isNotBlank() }) {
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    ListItem(
                                        headlineContent = { Text("Bot session") },
                                        supportingContent = { Text(if (bot.botEnabled) "Enabled · polling Telegram Bot API updates" else "Token saved · session paused") },
                                        leadingContent = { Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary) }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            ListItem(
                                modifier = Modifier.clickable { recentChatsExpanded = !recentChatsExpanded },
                                headlineContent = { Text("Recent chats") },
                                supportingContent = { Text(if (!ready) "Connect this account to load recent chats" else if (chats.isEmpty()) "No recent chats available" else "${minOf(chats.size, 20)} chats · tap to ${if (recentChatsExpanded) "hide" else "expand"}") },
                                leadingContent = { Icon(Icons.Rounded.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(if (recentChatsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
                            )
                        }
                    }
                    if (recentChatsExpanded && (!ready || chats.isEmpty())) {
                        item {
                            Card(shape = RoundedCornerShape(18.dp)) {
                                ListItem(
                                    headlineContent = { Text(if (!ready) "Account not connected" else "No chats loaded") },
                                    supportingContent = { Text(if (!ready) "Complete Telegram sign-in to view your chats." else "Telegram has not returned any chats yet.") },
                                    leadingContent = { Icon(Icons.Rounded.ChatBubbleOutline, null) }
                                )
                            }
                        }
                    } else if (recentChatsExpanded) {
                        items(chats.take(20), key = { "insight-chat-${it.id}" }) { chat ->
                            Card(shape = RoundedCornerShape(18.dp)) {
                                ListItem(
                                    headlineContent = { Text(chat.title.ifBlank { "Chat ${chat.id}" }, maxLines = 1) },
                                    supportingContent = {
                                        Text(when (chat.type) {
                                            is TdApi.ChatTypePrivate -> "Personal chat"
                                            is TdApi.ChatTypeBasicGroup -> "Group"
                                            is TdApi.ChatTypeSupergroup -> if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "Channel" else "Supergroup"
                                            is TdApi.ChatTypeSecret -> "Secret chat"
                                            else -> "Telegram chat"
                                        }, maxLines = 1)
                                    },
                                    leadingContent = { Icon(Icons.Rounded.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary) }
                                )
                            }
                        }
                    }
            }
            }
            UserbotSection.SETTINGS -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 18.dp),
                contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        ListItem(headlineContent = { Text("Profile name") }, supportingContent = { Text(bot.name) },
                            leadingContent = { Icon(Icons.Rounded.Edit, null) },
                            trailingContent = { TextButton(onClick = { renamedProfile = bot.name; showRenameDialog = true }) { Text("Rename") } })
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        ListItem(headlineContent = { Text("Telegram session") }, supportingContent = { Text("Log out from this account on this device.") },
                            leadingContent = { Icon(Icons.Rounded.Logout, null) },
                            trailingContent = { TextButton(onClick = onLogout) { Text("Log out") } })
                    }
                }
                item {
                    Text("Userbot settings", style = MaterialTheme.typography.headlineSmall)
                    Text("Manage this account profile and its session.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Card(shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Delete userbot", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("Permanently remove ${bot.name}, its encrypted credentials and its local TDLib session data.",
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Button(onClick = onDelete, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                                Icon(Icons.Rounded.DeleteOutline, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Delete this userbot")
                            }
                        }
                    }
                }
            }
        }
        }
    }
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename userbot") },
            text = { OutlinedTextField(renamedProfile, { renamedProfile = it.take(48) }, label = { Text("Display name") }, singleLine = true) },
            confirmButton = { Button(onClick = { onRename(renamedProfile.trim()); showRenameDialog = false }, enabled = renamedProfile.isNotBlank() && renamedProfile.trim() != bot.name) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsScreen(modifier: Modifier, selectedOwner: Int, onOwnerChange: (Int) -> Unit, onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { PluginSettings(context) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var handler by remember { mutableStateOf(settings.handler()) }
    var savedPrefix by remember { mutableStateOf(settings.handler()) }

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        PrimaryTabRow(selectedTabIndex = selectedOwner, modifier = Modifier.padding(top = 4.dp)) {
            Tab(selected = selectedOwner == 0, onClick = { onOwnerChange(0) }, text = { Text("Userbot") },
                icon = { Icon(Icons.Rounded.Person, null) })
            Tab(selected = selectedOwner == 1, onClick = { onOwnerChange(1) }, text = { Text("Bot") },
                icon = { Icon(Icons.Rounded.SmartToy, null) })
        }

        if (selectedOwner == 0) {
            Text("Command handler", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = handler,
                    onValueChange = { handler = it.take(8) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Command prefix") },
                    supportingText = { Text("Example: .ban, .mute, .afk") },
                    singleLine = true
                )
                Button(
                    onClick = {
                        val saved = handler.trim().ifBlank { "." }
                        settings.setHandler(saved)
                        handler = saved
                        savedPrefix = saved
                        focusManager.clearFocus()
                        keyboard?.hide()
                    },
                    enabled = handler.isNotBlank() && handler.trim() != savedPrefix
                ) { Text("Save") }
            }
        }

        val plugins = if (selectedOwner == 0) PluginCatalog.userbot else PluginCatalog.bot
        Text(
            if (selectedOwner == 0) "Userbot plugin library · " + plugins.size
            else "Bot plugin library · " + plugins.size,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(plugins, key = { it.id }) { plugin ->
                Card(onClick = { onOpen(plugin.id) }, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (plugin.owner == com.vikram.trondroid.plugins.PluginOwner.BOT)
                                        Icons.Rounded.SmartToy else Icons.Rounded.Extension,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(plugin.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                plugin.category + " · " + plugin.commands.size + " commands · " + (if (plugin.implemented) "Native" else "Not ported"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginDetailScreen(modifier: Modifier, id: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val plugin = PluginCatalog.find(id) ?: return
    val isBot = plugin.owner == com.vikram.trondroid.plugins.PluginOwner.BOT
    val pluginSettings = remember { PluginSettings(context) }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isBot) Icons.Rounded.SmartToy else Icons.Rounded.Extension,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(plugin.owner.label + " plugin", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(plugin.summary, style = MaterialTheme.typography.bodyLarge)
                    Text(plugin.category, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (plugin.implemented) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(if (plugin.implemented) "Native Android implementation" else "Not yet ported to Android",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (plugin.implemented) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
        item {
            Text("Commands", style = MaterialTheme.typography.titleMedium)
        }
        items(plugin.commands, key = { it.name }) { command ->
            var commandEnabled by remember(plugin.id, command.name) {
                mutableStateOf(pluginSettings.commandEnabled(plugin.id, command.name))
            }
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (isBot) "/" else pluginSettings.handler()) + command.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = commandEnabled,
                            onCheckedChange = {
                                commandEnabled = it
                                pluginSettings.setCommandEnabled(plugin.id, command.name, it)
                            }
                        )
                    }
                    Text(command.description, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Usage: " + (if (isBot) "/" else pluginSettings.handler()) + command.usage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Example: " + (if (isBot) "/" else pluginSettings.handler()) + command.example,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                ListItem(
                    headlineContent = { Text("Source module") },
                    supportingContent = { Text(plugin.sourcePath) },
                    leadingContent = { Icon(Icons.Rounded.Code, null) }
                )
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/TronUb/Tron/blob/master/" + plugin.sourcePath)
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("View source on GitHub")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(modifier: Modifier, onGeneral: () -> Unit, onAbout: () -> Unit, onUpdater: () -> Unit, themeMode: String, onThemeChange: (String) -> Unit, dynamicColors: Boolean, onDynamicColorsChange: (Boolean) -> Unit) {
    Column(modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())) {
        Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system", "light", "dark").forEachIndexed { index, mode ->
                        SegmentedButton(selected = themeMode == mode, onClick = { onThemeChange(mode) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                            Text(mode.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic color", style = MaterialTheme.typography.titleSmall)
                        Text("Match the app to your wallpaper.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = dynamicColors, onCheckedChange = onDynamicColorsChange)
                }
            }
        }
        SettingsGroup(
            "Userbot & bot",
            listOf(
                Triple("Session preferences", "Background account and connection behaviour", Icons.Rounded.Tune),
                Triple("Bot connections", "Manage bot tokens and bot sessions per account", Icons.Rounded.SmartToy)
            ),
            listOf(onGeneral, onGeneral)
        )
        SettingsGroup(
            "Application",
            listOf(
                Triple("App updates", "Check and install the latest APK", Icons.Rounded.SystemUpdate),
                Triple("About TronDroid", "App details, project links and developer information", Icons.Rounded.Info)
            ),
            listOf(onUpdater, onAbout)
        )

    }
}


@Composable
private fun AboutScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), modifier = Modifier.size(58.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Send, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                    Text("TronDroid", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("A Telegram account, userbot and bot automation manager for Android.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Version " + com.vikram.trondroid.BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Text("What is TronDroid?", style = MaterialTheme.typography.titleMedium)
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("One place to manage multiple Telegram userbot sessions, connect Bot API accounts, run command plugins and inspect account activity.")
                    Text("Userbot sessions run on TDLib. Bot accounts use the official Telegram Bot API. Account credentials are encrypted locally with Android Keystore.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Developer", style = MaterialTheme.typography.titleMedium)
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column {
                    ListItem(headlineContent = { Text("Vikram Vishwakarma") },
                        supportingContent = { Text("Android and software developer · UI/UX engineering") },
                        leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/hustlewithvikram")))
                    }, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) {
                        Icon(Icons.Rounded.OpenInNew, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Developer profile")
                    }
                }
            }
        }
        item {
            Text("Project", style = MaterialTheme.typography.titleMedium)
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                ListItem(headlineContent = { Text("TronDroid on GitHub") },
                    supportingContent = { Text("Source code, issues and development updates") },
                    leadingContent = { Icon(Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Rounded.OpenInNew, null) },
                    modifier = Modifier.clickable {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/TronUb/TronDroid")))
                    })
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                ListItem(headlineContent = { Text("Application ID") },
                    supportingContent = { Text(com.vikram.trondroid.BuildConfig.APPLICATION_ID) },
                    leadingContent = { Icon(Icons.Rounded.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    entries: List<Triple<String, String, androidx.compose.ui.graphics.vector.ImageVector>>,
    actions: List<() -> Unit>
) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        entries.forEachIndexed { index, entry ->
            val shape = when {
                entries.size == 1 -> RoundedCornerShape(20.dp)
                index == 0 -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
                index == entries.lastIndex -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                else -> RoundedCornerShape(8.dp)
            }
            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .clickable { actions.getOrNull(index)?.invoke() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
            }
        }
    }
}

@Composable
private fun GeneralSettingsScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("trondroid.settings", android.content.Context.MODE_PRIVATE) }
    var autoStart by remember { mutableStateOf(preferences.getBoolean("auto_start_sessions", true)) }
    var autoReconnectBots by remember { mutableStateOf(preferences.getBoolean("auto_reconnect_bots", true)) }
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Userbot & bot preferences", style = MaterialTheme.typography.headlineSmall)
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-start sessions", style = MaterialTheme.typography.titleSmall)
                        Text("Reconnect enabled accounts when TronDroid launches.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoStart, onCheckedChange = {
                        autoStart = it
                        preferences.edit().putBoolean("auto_start_sessions", it).apply()
                    })
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-reconnect bots", style = MaterialTheme.typography.titleSmall)
                        Text("Retry Bot API connections after temporary network or API errors.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoReconnectBots, onCheckedChange = {
                        autoReconnectBots = it
                        preferences.edit().putBoolean("auto_reconnect_bots", it).apply()
                    })
                }
            }
        }
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Background reliability", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Android can pause network activity when the app is restricted. Review these system settings to keep enabled Telegram sessions connected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.BatteryFull, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open battery optimization settings")
                }
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Notifications, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open notification settings")
                }
            }
        }
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                ListItem(
                    headlineContent = { Text("Userbot sessions") },
                    supportingContent = { Text("Each enabled userbot runs in its own TDLib session. Manage account toggles from the account's General tab.") },
                    leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("Bot sessions") },
                    supportingContent = { Text("Add BOT_TOKEN under an account's Credentials tab. Its bot card and background toggle appear on that account's General tab.") },
                    leadingContent = { Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary) }
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("Credential security") },
                    supportingContent = { Text("API hashes and custom key/value pairs are encrypted locally using Android Keystore.") },
                    leadingContent = { Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary) }
                )
            }
        }
        Text("Application information", style = MaterialTheme.typography.titleMedium)
        Card(shape = RoundedCornerShape(20.dp)) {
            ListItem(headlineContent = { Text("TronDroid 1.0.0") },
                supportingContent = { Text("Debug: com.vikram.trondroid.debug · Release: com.vikram.trondroid") },
                leadingContent = { Icon(Icons.Rounded.Info, null) })
        }
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
            Card(onClick = { onOpen(bot.id) }, shape = RoundedCornerShape(18.dp)) {
                ListItem(headlineContent = { Text(bot.name) }, supportingContent = { Text(bot.phoneNumber) },
                    leadingContent = { Icon(Icons.Rounded.Key, null) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) })
            }
        }
        if (bots.isEmpty()) item { Text("Add a userbot first to manage its credentials.") }
    }
}

@Composable
private fun CredentialDetailScreen(
    modifier: Modifier,
    bot: BotProfile,
    apiHash: String,
    onSaveApiHash: (String) -> Unit,
    customCredentials: Map<String, String>,
    onSaveCustomCredentials: (Map<String, String>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var hash by remember(bot.id, apiHash) { mutableStateOf(apiHash) }
    var showCustomCredentialsDialog by remember { mutableStateOf(false) }
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
            Card(shape = RoundedCornerShape(16.dp)) {
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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Custom credentials", style = MaterialTheme.typography.titleMedium)
                        Text("Extra key/value pairs stored securely for this account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { showCustomCredentialsDialog = true }) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add more")
                    }

                }
                if (customCredentials.isEmpty()) {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Text("No custom credentials added.", Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    customCredentials.toSortedMap().forEach { (key, value) ->
                        CustomCredentialField(key, value)
                    }
                }
                if (customCredentials.isNotEmpty()) {
                    TextButton(onClick = { onSaveCustomCredentials(emptyMap()) }, modifier = Modifier.align(Alignment.End)) {
                        Text("Clear all credentials", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item { CredentialField("TDLib session", context.filesDir.resolve("tdlib/" + bot.id).absolutePath) }
    }
    if (showCustomCredentialsDialog) {
        CustomCredentialsDialog(
            existing = customCredentials,
            onDismiss = { showCustomCredentialsDialog = false },
            onConfirm = { values ->
                onSaveCustomCredentials(values)
                showCustomCredentialsDialog = false
            }
        )
    }
}

@Composable
private fun CustomCredentialField(keyName: String, value: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var reveal by remember(keyName) { mutableStateOf(false) }
    val sensitive = keyName.contains("TOKEN", true) || keyName.contains("HASH", true) ||
        keyName.contains("SECRET", true) || keyName.contains("PASSWORD", true)
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(keyName, style = MaterialTheme.typography.labelLarge)
            Text(
                if (sensitive && !reveal) "••••••••••••••••" else value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sensitive) TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                IconButton(onClick = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText(keyName, value))
                }) { Icon(Icons.Rounded.ContentCopy, "Copy $keyName") }
            }
        }
    }
}

@Composable
private fun CustomCredentialsDialog(
    existing: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit
) {
    val rows = remember(existing) {
        androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply {
            addAll(existing.toSortedMap().map { it.key to it.value })
            if (isEmpty()) add("" to "")
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom credentials") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add one or more key/value pairs. Values are encrypted locally on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEachIndexed { index, pair ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = pair.first,
                                onValueChange = { rows[index] = rows[index].copy(first = it); error = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Key name") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = pair.second,
                                onValueChange = { rows[index] = rows[index].copy(second = it); error = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Value") },
                                minLines = 1,
                                maxLines = 3
                            )
                            if (rows.size > 1) {
                                TextButton(onClick = { rows.removeAt(index) }, modifier = Modifier.align(Alignment.End)) {
                                    Icon(Icons.Rounded.DeleteOutline, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { rows.add("" to "") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add more")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val filled = rows.filter { it.first.isNotBlank() && it.second.isNotBlank() }
                val duplicate = filled.groupBy { it.first.trim().uppercase() }.any { it.value.size > 1 }
                when {
                    filled.isEmpty() -> error = "Enter at least one key and value."
                    duplicate -> error = "Key names must be unique."
                    rows.any { it.first.isBlank() != it.second.isBlank() } -> error = "Complete both fields for every row."
                    else -> onConfirm(filled.associate { it.first.trim() to it.second.trim() })
                }
            }) { Text("Confirm") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CredentialField(label: String, value: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(shape = RoundedCornerShape(16.dp)) {
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
