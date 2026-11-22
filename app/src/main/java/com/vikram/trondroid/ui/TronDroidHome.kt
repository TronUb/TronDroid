package com.vikram.trondroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import com.vikram.trondroid.plugins.ExternalPluginLoader
import com.vikram.trondroid.plugins.PluginSettings
import com.vikram.trondroid.telegram.TelegramEngine
import com.vikram.trondroid.telegram.TelegramBotRuntime
import com.vikram.trondroid.plugins.PluginOwner
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab { USERBOTS, PLUGINS, SETTINGS }
private sealed interface Page {
    data object Home : Page
    data class Userbot(val id: String) : Page
    data object SessionPreferences : Page
    data object BotConnections : Page
    data object Permissions : Page
    data object About : Page
    data object Updater : Page
    data object SettingsBackup : Page
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
    dynamicColors: Boolean, onDynamicColorsChange: (Boolean) -> Unit,
    onExportSettings: () -> String,
    onImportSettings: (String) -> Unit
) {
    var tab by remember { mutableStateOf(Tab.USERBOTS) }
    var pageStack by remember { mutableStateOf(listOf<Page>(Page.Home)) }
    var navigationDirection by remember { mutableIntStateOf(1) }
    val page = pageStack.last()
    fun navigate(next: Page) { navigationDirection = 1; pageStack = pageStack + next }
    fun goBack() { if (pageStack.size > 1) { navigationDirection = -1; pageStack = pageStack.dropLast(1) } }
    var showAdd by remember { mutableStateOf(false) }
    var pluginOwner by remember { mutableIntStateOf(0) }
    val userbotsListState = rememberLazyListState()
    val userbotPluginsListState = rememberLazyListState()
    val botPluginsListState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<BotProfile?>(null) }
    var pendingLogout by remember { mutableStateOf<BotProfile?>(null) }
    var importingPlugin by remember { mutableStateOf(false) }
    val importPluginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importingPlugin = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { ExternalPluginLoader.importManifest(context, uri) }
                }
                android.widget.Toast.makeText(
                    context,
                    result.fold(
                        { "Imported $it manifest. Metadata only; external code is not executed." },
                        { "Plugin import failed: ${it.message ?: "Invalid manifest"}" }
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                importingPlugin = false
            }
        }
    }
    val isHome = page == Page.Home
    Scaffold(
        topBar = {},
        bottomBar = {},
        floatingActionButton = {
            if (isHome && tab == Tab.USERBOTS) {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.offset(y = (-104).dp)
                ) {
                    Icon(Icons.Rounded.Add, "Add userbot")
                }
            } else if (isHome && tab == Tab.PLUGINS) {
                FloatingActionButton(
                    onClick = {
                        if (!importingPlugin) {
                            importPluginLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                        }
                    },
                    modifier = Modifier.offset(y = (-104).dp)
                ) {
                    if (importingPlugin) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.DownloadForOffline, "Import external plugin manifest")
                    }
                }
            }
        }
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(modifier = Modifier.fillMaxSize().statusBarsPadding(), targetState = page, transitionSpec = {
            val direction = navigationDirection
            (slideInHorizontally { width -> if (direction > 0) width else -width } + fadeIn()) togetherWith
                (slideOutHorizontally { width -> if (direction > 0) -width else width } + fadeOut())
        }, label = "page-slide") { currentPage ->
        when (val current = currentPage) {
            Page.Home -> when (tab) {
                Tab.USERBOTS -> UserbotsScreen(Modifier.fillMaxSize(), bots, listState = userbotsListState, onOpen = {
                    onSelect(it); navigate(Page.Userbot(it))
                }, onToggle = { id, enabled -> onToggle(id, enabled) },
                    onBotToggle = { id, enabled -> onBotToggle(id, enabled) })
                Tab.PLUGINS -> PluginsScreen(Modifier.fillMaxSize(), selectedOwner = pluginOwner, onOwnerChange = { pluginOwner = it }, listState = if (pluginOwner == 0) userbotPluginsListState else botPluginsListState, onOpen = { navigate(Page.PluginDetail(it)) })
                Tab.SETTINGS -> SettingsScreen(Modifier.fillMaxSize(),
                    onSessions = { navigate(Page.SessionPreferences) }, onBots = { navigate(Page.BotConnections) },
                    onPermissions = { navigate(Page.Permissions) }, onAbout = { navigate(Page.About) }, onUpdater = { navigate(Page.Updater) },
                    onBackup = { navigate(Page.SettingsBackup) },
                    themeMode = themeMode, onThemeChange = onThemeChange, dynamicColors = dynamicColors, onDynamicColorsChange = onDynamicColorsChange)
            }
            is Page.Userbot -> {
                val bot = bots.firstOrNull { it.id == current.id }
                UserbotDetailScreen(
                    Modifier.fillMaxSize(),
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
                    onBotToggle = onBotToggle,
                    onBack = { goBack() }
                )
            }
            Page.SessionPreferences -> SessionPreferencesScreen(Modifier.fillMaxSize())
            Page.BotConnections -> BotConnectionsScreen(Modifier.fillMaxSize())
            Page.Permissions -> PermissionsScreen(Modifier.fillMaxSize())
            Page.About -> AboutScreen(Modifier.fillMaxSize())
            Page.Updater -> AppUpdaterScreen(Modifier.fillMaxSize())
            Page.SettingsBackup -> SettingsBackupScreen(
                Modifier.fillMaxSize(),
                onBack = { goBack() },
                exportJson = onExportSettings,
                importJson = onImportSettings
            )
            is Page.PluginDetail -> PluginDetailScreen(Modifier.fillMaxSize(), current.id)
            }
        }
            if (isHome) {
                FloatingGlassBottomBar(
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
    BackHandler(enabled = pageStack.size > 1 || tab != Tab.USERBOTS) { if (pageStack.size > 1) goBack() else tab = Tab.USERBOTS }
    if (showAdd) AddBotDialog(onDismiss = { showAdd = false }) { name, phone, id, hash ->
        onAdd(name, phone, id, hash); showAdd = false
    }
    pendingLogout?.let { bot ->
        AlertDialog(onDismissRequest = { pendingLogout = null }, title = { Text("Log out of ${bot.name}?") },
            text = { Text("This will end the Telegram session on this device. You can sign in again later.") },
            confirmButton = {
                Button(
                    onClick = {
                        val activeEngine = if (selected?.id == bot.id) engine else null
                        if (activeEngine == null) {
                            onToggle(bot.id, false)
                        } else {
                            activeEngine.logout { onToggle(bot.id, false) }
                        }
                        pendingLogout = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Log out") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingLogout = null }) { Text("Cancel") }
            })
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
private fun FloatingGlassBottomBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(Tab.USERBOTS, Tab.PLUGINS, Tab.SETTINGS)
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val glass = if (dark) Color(0xFF172238).copy(alpha = 0.96f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
    val edge = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.24f else 0.18f)
    Box(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp)
            .height(76.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(66.dp)
                .clip(RoundedCornerShape(50))
                .background(glass)
                .border(1.dp, edge, RoundedCornerShape(50))
                .padding(5.dp)
        ) {
            val itemWidth = maxWidth / 3
            val animatedX by androidx.compose.animation.core.animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 520f),
                label = "glass-tab-indicator"
            )
            Box(Modifier.offset(x = animatedX).width(itemWidth).fillMaxHeight()
                .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)))
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    val active = selected == tab
                    val icon = when (tab) {
                        Tab.USERBOTS -> Icons.Rounded.SmartToy
                        Tab.PLUGINS -> Icons.Rounded.Extension
                        Tab.SETTINGS -> Icons.Rounded.Settings
                    }
                    val label = when (tab) {
                        Tab.USERBOTS -> "Userbots"
                        Tab.PLUGINS -> "Plugins"
                        Tab.SETTINGS -> "Settings"
                    }
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).clickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserbotsScreen(
    modifier: Modifier,
    bots: List<BotProfile>,
    listState: LazyListState,
    onOpen: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onBotToggle: (String, Boolean) -> Unit
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Userbots", style = MaterialTheme.typography.headlineMedium)
            Text("Manage your Telegram account sessions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                    Switch(
                        checked = bot.enabled || bot.botEnabled,
                        onCheckedChange = { enabled ->
                            onToggle(bot.id, enabled)
                            onBotToggle(bot.id, enabled)
                        }
                    )
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class UserbotSection(val label: String) {
    companion object {
        val GENERAL = UserbotSection("General")
        val LOGS = UserbotSection("Logs")
        val CREDENTIALS = UserbotSection("Credentials")
        val INSIGHTS = UserbotSection("Insights")
        val SETTINGS = UserbotSection("Settings")
    }
}

private val userbotSections = listOf(
    UserbotSection.GENERAL, UserbotSection.LOGS, UserbotSection.CREDENTIALS,
    UserbotSection.INSIGHTS, UserbotSection.SETTINGS
)

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
    onBotToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    if (bot == null) return
    // Keep Flow collection slots stable when the engine disappears after a userbot is disabled.
    // Conditional collectAsState calls can shift Compose's remembered slots and crash this screen.
    val authFlow = remember(engine) {
        engine?.authorization ?: kotlinx.coroutines.flow.MutableStateFlow<TdApi.AuthorizationState?>(null)
    }
    val logsFlow = remember(engine) {
        engine?.logs ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.vikram.trondroid.telegram.EngineLog>())
    }
    val chatsFlow = remember(engine) {
        engine?.chats ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<TdApi.Chat>())
    }
    val accountFlow = remember(engine) {
        engine?.account ?: kotlinx.coroutines.flow.MutableStateFlow<com.vikram.trondroid.telegram.TelegramAccountInfo?>(null)
    }
    val auth by authFlow.collectAsState()
    val logs by logsFlow.collectAsState()
    val chats by chatsFlow.collectAsState()
    val account by accountFlow.collectAsState()
    val accountAvatar = remember(account?.photoPath) { account?.photoPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    val botAvatar = remember(bot.botPhotoPath) { bot.botPhotoPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    val ready = auth?.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR
    var section by remember(bot.id) { mutableStateOf(UserbotSection.GENERAL) }
    var recentChatsExpanded by remember(bot.id) { mutableStateOf(false) }
    var terminalFullscreen by remember(bot.id) { mutableStateOf(false) }
    var showRenameDialog by remember(bot.id) { mutableStateOf(false) }
    var renamedProfile by remember(bot.id, bot.name) { mutableStateOf(bot.name) }
    val terminal = androidx.compose.ui.graphics.Color(0xFF050705)
    val terminalText = androidx.compose.ui.graphics.Color(0xFFB8D8C0)
    val terminalAccent = androidx.compose.ui.graphics.Color(0xFF9BE7B0)

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Configuration", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Manage Telegram sessions, credentials and automation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ScrollableTabRow(
            selectedTabIndex = userbotSections.indexOf(section),
            edgePadding = 16.dp,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)) }
        ) {
            userbotSections.forEach { item ->
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
                                else -> Icons.Rounded.Settings
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
                        val index = userbotSections.indexOf(section)
                        if (dragDistance < -72f && index < userbotSections.lastIndex) {
                            section = userbotSections[index + 1]
                        } else if (dragDistance > 72f && index > 0) {
                            section = userbotSections[index - 1]
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
                                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
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
                        IconButton(onClick = { terminalFullscreen = true }) {
                            Icon(Icons.Rounded.Fullscreen, "Open terminal fullscreen", tint = terminalAccent)
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
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Total conversations", style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
                                    Text(chats.size.toString(), style = MaterialTheme.typography.displaySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("Chats synchronized with this account", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
                                }
                                Icon(Icons.Rounded.Forum, null, Modifier.size(42.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
                            }
                        }
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
                                        LinearProgressIndicator(
                                            progress = { if (chats.isEmpty()) 0f else (count.toFloat() / chats.size).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
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
                    Text("ACCOUNT", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        ListItem(headlineContent = { Text("Profile name") }, supportingContent = { Text(bot.name) },
                            leadingContent = { Icon(Icons.Rounded.Edit, null) },
                            trailingContent = { TextButton(onClick = { renamedProfile = bot.name; showRenameDialog = true }) { Text("Rename") } })
                    }
                }
                item {
                    Text("SESSION", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
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
                    Text("DANGER ZONE", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
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
    if (terminalFullscreen) {
        Dialog(
            onDismissRequest = { terminalFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                Modifier.fillMaxWidth().fillMaxHeight(0.96f).padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = terminal),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Code, null, tint = terminalAccent)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("TRONDROID / SESSION", color = terminalAccent, style = MaterialTheme.typography.titleSmall)
                            Text("LIVE LOG STREAM · ${logs.size} EVENTS", color = terminalText.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { terminalFullscreen = false }) {
                            Icon(Icons.Rounded.FullscreenExit, "Exit fullscreen", tint = terminalAccent)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF26352A))
                    if (logs.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("> waiting for events…", color = terminalText)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 18.dp)) {
                            items(logs.takeLast(1000)) { entry ->
                                Text(
                                    "[${entry.time}] ${if (entry.isError) "ERROR" else "INFO"}  ${entry.message}",
                                    color = if (entry.isError) Color(0xFFFF8A80) else terminalText,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                )
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = countryMenu,
                    onExpandedChange = { countryMenu = it },
                    modifier = Modifier.width(112.dp)
                ) {
                    OutlinedTextField(
                        value = country.dial,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Code", maxLines = 1, softWrap = false) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(countryMenu) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = countryMenu, onDismissRequest = { countryMenu = false }) {
                        countries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.name}  ${c.dial}") },
                                onClick = { country = c; countryMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                    label = { Text("Mobile", maxLines = 1, softWrap = false) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
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
private fun PluginsScreen(modifier: Modifier, selectedOwner: Int, onOwnerChange: (Int) -> Unit, listState: LazyListState, onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { PluginSettings(context) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var handler by remember { mutableStateOf(settings.handler()) }
    var savedPrefix by remember { mutableStateOf(settings.handler()) }
    var search by remember(selectedOwner) { mutableStateOf("") }
    val allPlugins = if (selectedOwner == 0) PluginCatalog.userbot else PluginCatalog.bot
    val plugins = remember(allPlugins, search) {
        if (search.isBlank()) allPlugins else allPlugins.filter { plugin ->
            plugin.title.contains(search, true) || plugin.summary.contains(search, true) ||
                plugin.category.contains(search, true) || plugin.commands.any { it.name.contains(search, true) || it.description.contains(search, true) }
        }
    }
    val enabledCount = allPlugins.count { settings.enabled(it.id) }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Plugins", style = MaterialTheme.typography.headlineMedium)
            Text("Manage automation tools and individual commands.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Userbot", "Bot").forEachIndexed { index, label ->
                        val active = selectedOwner == index
                        Surface(onClick = { if (!active) onOwnerChange(index) }, shape = RoundedCornerShape(18.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.weight(1f)) {
                            Row(Modifier.padding(vertical = 11.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (index == 0) Icons.Rounded.Person else Icons.Rounded.SmartToy, null,
                                    tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(label, style = MaterialTheme.typography.labelLarge,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            val isUserbot = selectedOwner == 0
            val shownPrefix = if (isUserbot) handler else "/"
            Card(shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Icon(Icons.Rounded.Terminal, null, Modifier.padding(9.dp).size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Command prefix", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (isUserbot) "Used before every userbot command" else "Telegram Bot API command prefix",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(shape = RoundedCornerShape(50.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(if (isUserbot) savedPrefix else "/", Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            TextField(
                                value = shownPrefix,
                                onValueChange = { if (isUserbot) handler = it.take(8) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isUserbot,
                                readOnly = !isUserbot,
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                placeholder = { Text(if (isUserbot) "." else "/") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                            Text(
                                if (isUserbot) "Choose a short prefix, e.g. . or !"
                                else "Telegram Bot API requires / for bot commands; custom prefixes aren't supported.",
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                val value = handler.trim().ifBlank { "." }
                                settings.setHandler(value)
                                handler = value
                                savedPrefix = value
                                focusManager.clearFocus()
                                keyboard?.hide()
                            },
                            enabled = isUserbot && handler.isNotBlank() && handler.trim() != savedPrefix,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 15.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (selectedOwner == 0) "Userbot plugins" else "Bot plugins", style = MaterialTheme.typography.titleMedium)
                    Text("$enabledCount enabled of ${allPlugins.size}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Rounded.Extension, null, Modifier.padding(10.dp).size(21.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        item {
            TextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Search plugins or commands") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
        if (plugins.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.SearchOff, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No matching plugins", style = MaterialTheme.typography.titleSmall)
                Text("Try another name or command.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(plugins, key = { it.id }) { plugin ->
            var enabled by remember(plugin.id) { mutableStateOf(settings.enabled(plugin.id)) }
            Card(onClick = { onOpen(plugin.id) }, shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (selectedOwner == 0) Icons.Rounded.Extension else Icons.Rounded.SmartToy, null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(plugin.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("${plugin.category} · ${plugin.commands.size} commands", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(plugin.summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it; settings.setEnabled(plugin.id, it) })
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun PluginDetailScreen(modifier: Modifier, id: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val plugin = PluginCatalog.find(id) ?: return
    val isBot = plugin.owner == PluginOwner.BOT
    val settings = remember { PluginSettings(context) }
    val listState = rememberLazyListState()
    val prefix = if (isBot) "/" else settings.handler()
    var refresh by remember { mutableIntStateOf(0) }
    val enabledCommands = plugin.commands.count { settings.commandEnabled(plugin.id, it.name) }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(if (isBot) Icons.Rounded.SmartToy else Icons.Rounded.Extension, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(25.dp)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(plugin.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(plugin.owner.label + " plugin · " + plugin.category, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                        }
                    }
                    Text(plugin.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                            Text(if (plugin.implemented) "Native Android" else "Not ported", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                            Text("$enabledCommands / ${plugin.commands.size} commands enabled", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Commands", style = MaterialTheme.typography.titleLarge)
                    Text("Enable only the actions you want available in chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { plugin.commands.forEach { settings.setCommandEnabled(plugin.id, it.name, true) }; refresh++ }) { Text("Enable all") }
                TextButton(onClick = { plugin.commands.forEach { settings.setCommandEnabled(plugin.id, it.name, false) }; refresh++ }) { Text("Disable all") }
            }
        }
        items(plugin.commands, key = { it.name }) { command ->
            var commandEnabled by remember(plugin.id, command.name, refresh) { mutableStateOf(settings.commandEnabled(plugin.id, command.name)) }
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(prefix + command.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(if (commandEnabled) "Enabled" else "Disabled", style = MaterialTheme.typography.labelSmall,
                                color = if (commandEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = commandEnabled, onCheckedChange = {
                            commandEnabled = it; settings.setCommandEnabled(plugin.id, command.name, it)
                        })
                    }
                    Text(command.description, style = MaterialTheme.typography.bodyMedium)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("USAGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prefix + command.usage, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("EXAMPLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prefix + command.example, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier,
    onSessions: () -> Unit,
    onBots: () -> Unit,
    onPermissions: () -> Unit,
    onAbout: () -> Unit,
    onUpdater: () -> Unit,
    onBackup: () -> Unit,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    dynamicColors: Boolean,
    onDynamicColorsChange: (Boolean) -> Unit
) {
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Personalize the app and manage connections.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system", "light", "dark").forEachIndexed { index, mode ->
                        SegmentedButton(selected = themeMode == mode, onClick = { onThemeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                            Text(mode.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic color", style = MaterialTheme.typography.titleSmall)
                        Text("Match TronDroid to your wallpaper.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = dynamicColors, onCheckedChange = onDynamicColorsChange)
                }
            }
        }
        SettingsGroup("Connections", listOf(
            Triple("Session preferences", "Userbot startup and background session behaviour", Icons.Rounded.Tune),
            Triple("Bot connections", "Bot API reconnect and connection behaviour", Icons.Rounded.SmartToy)
        ), listOf(onSessions, onBots))
        SettingsGroup("Device access", listOf(
            Triple("Permissions", "Required, optional and battery/background access", Icons.Rounded.Security)
        ), listOf(onPermissions))
        SettingsGroup("Application", listOf(
            Triple("Import / Export", "Back up and restore app settings, accounts and plugin states", Icons.Rounded.ImportExport),
            Triple("App updates", "Check and install the latest APK", Icons.Rounded.SystemUpdate),
            Triple("About TronDroid", "Version, project information and developer", Icons.Rounded.Info)
        ), listOf(onBackup, onUpdater, onAbout))
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun SettingsBackupScreen(
    modifier: Modifier,
    onBack: () -> Unit,
    exportJson: () -> String,
    importJson: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportJson()) }
                    ?: error("Unable to open the selected destination.")
            }
            android.widget.Toast.makeText(
                context,
                result.fold({ "Settings backup exported." }, { "Export failed: ${it.message}" }),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to read the selected backup.")
                require(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Backup is larger than 2 MB." }
                importJson(raw)
            }
            android.widget.Toast.makeText(
                context,
                result.fold({ "Settings backup restored." }, { "Import failed: ${it.message ?: "Invalid backup"}" }),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column {
                Text("Import / Export", style = MaterialTheme.typography.headlineSmall)
                Text("Back up and restore your TronDroid configuration.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Text("Export backup", style = MaterialTheme.typography.titleMedium)
                Text("Save app preferences, account profiles, encrypted credential records, and enabled/disabled plugin and command states into a JSON file.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { exportLauncher.launch("trondroid-backup.json") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FileDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export JSON")
                }
            }
        }
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Text("Import backup", style = MaterialTheme.typography.titleMedium)
                Text("Restore a previously exported TronDroid JSON backup. Import replaces the matching saved preferences and account records.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FileOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import JSON")
                }
            }
        }
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Text(
                "Security note: credential records are exported in their locally encrypted form. A backup restored on another device may not be able to decrypt secrets protected by the original device's Android Keystore.",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun AboutScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = com.vikram.trondroid.BuildConfig.VERSION_NAME
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 30.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("About TronDroid", style = MaterialTheme.typography.headlineMedium)
                Text("Project details and app information.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), modifier = Modifier.size(64.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Send, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
                    }
                    Text("TronDroid", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("Telegram sessions, userbot automation and Bot API connections — managed from Android.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                            Text("Version $version", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                            Text("Android", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        item {
            Text("Built for Telegram", style = MaterialTheme.typography.titleMedium)
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ListItem(headlineContent = { Text("Userbot sessions") }, supportingContent = { Text("TDLib-powered account sessions with background processing.") },
                        leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) })
                    HorizontalDivider()
                    ListItem(headlineContent = { Text("Bot API connections") }, supportingContent = { Text("Connect and manage Telegram bots alongside user accounts.") },
                        leadingContent = { Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary) })
                    HorizontalDivider()
                    ListItem(headlineContent = { Text("Command plugins") }, supportingContent = { Text("Enable plugins and control individual commands.") },
                        leadingContent = { Icon(Icons.Rounded.Extension, null, tint = MaterialTheme.colorScheme.primary) })
                }
            }
        }
        item {
            Text("Developer", style = MaterialTheme.typography.titleMedium)
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                ListItem(headlineContent = { Text("Vikram Vishwakarma") },
                    supportingContent = { Text("Android development · UI/UX engineering") },
                    leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Rounded.OpenInNew, null) },
                    modifier = Modifier.clickable {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/hustlewithvikram")))
                    })
            }
        }
        item {
            Text("Project", style = MaterialTheme.typography.titleMedium)
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                ListItem(headlineContent = { Text("TronDroid on GitHub") },
                    supportingContent = { Text("Source code, issues and development progress") },
                    leadingContent = { Icon(Icons.Rounded.Code, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Rounded.OpenInNew, null) },
                    modifier = Modifier.clickable {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/TronUb/TronDroid")))
                    })
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("App identity", style = MaterialTheme.typography.titleSmall)
                    Text("Application ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(com.vikram.trondroid.BuildConfig.APPLICATION_ID, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Built for Android with Kotlin and Jetpack Compose.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
private fun SessionPreferencesScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("trondroid.settings", android.content.Context.MODE_PRIVATE) }
    var autoStart by remember { mutableStateOf(preferences.getBoolean("auto_start_sessions", true)) }
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Text("Session preferences", style = MaterialTheme.typography.headlineSmall)
            Text("Control how your enabled userbot sessions start and stay active.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-start sessions", style = MaterialTheme.typography.titleMedium)
                        Text("Reconnect enabled userbot sessions when TronDroid launches.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoStart, onCheckedChange = {
                        autoStart = it
                        preferences.edit().putBoolean("auto_start_sessions", it).apply()
                    })
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background reliability", style = MaterialTheme.typography.titleMedium)
                    Text("For reliable background sessions, allow notifications and review battery restrictions in the Permissions page.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BotConnectionsScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("trondroid.settings", android.content.Context.MODE_PRIVATE) }
    var autoReconnect by remember { mutableStateOf(preferences.getBoolean("auto_reconnect_bots", true)) }
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Text("Bot connections", style = MaterialTheme.typography.headlineSmall)
            Text("Manage Bot API connection recovery independently from userbot sessions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-reconnect bots", style = MaterialTheme.typography.titleMedium)
                        Text("Retry Bot API connections after temporary network or API errors.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoReconnect, onCheckedChange = {
                        autoReconnect = it
                        preferences.edit().putBoolean("auto_reconnect_bots", it).apply()
                    })
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    ListItem(headlineContent = { Text("Connect a bot") },
                        supportingContent = { Text("Add BOT_TOKEN in an account's Credentials tab. The bot can then be enabled from that account's General tab.") },
                        leadingContent = { Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    ListItem(headlineContent = { Text("Credential security") },
                        supportingContent = { Text("Saved API hashes and custom credentials are encrypted locally using Android Keystore.") },
                        leadingContent = { Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary) })
                }
            }
        }
    }
}

@Composable
private fun PermissionsScreen(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var refresh by remember { mutableIntStateOf(0) }
    val notificationsGranted = if (android.os.Build.VERSION.SDK_INT >= 33)
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    else true
    val batteryAllowed = manager.isIgnoringBatteryOptimizations(context.packageName)
    val internetGranted = context.packageManager.checkPermission(android.Manifest.permission.INTERNET, context.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val networkGranted = context.packageManager.checkPermission(android.Manifest.permission.ACCESS_NETWORK_STATE, context.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val foregroundGranted = context.packageManager.checkPermission(android.Manifest.permission.FOREGROUND_SERVICE, context.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val installAllowed = if (android.os.Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls() else true
    fun openSettings(action: String) {
        runCatching { context.startActivity(android.content.Intent(action).apply {
            if (action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS ||
                action == android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                data = android.net.Uri.parse("package:" + context.packageName)
            else if (action == android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }) }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 30.dp)) {
        item {
            Text("App permissions", style = MaterialTheme.typography.headlineSmall)
            Text("Live status of Android permissions and special access used by TronDroid.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Access overview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Refresh") }
            }
            Text("Statuses update when you return to this page or tap Refresh.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Required", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column {
                    PermissionStatusRow("Internet access", "Required for Telegram API and media requests", true, if (internetGranted) "Granted" else "Missing")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    PermissionStatusRow("Network state", "Detects connectivity changes", true, if (networkGranted) "Granted" else "Missing")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    PermissionStatusRow("Foreground service", "Keeps user-enabled sessions running in the background", true, if (foregroundGranted) "Granted" else "Missing")
                }
            }
        }
        item {
            Text("Optional", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column {
                    PermissionStatusRow("Notifications", "Shows session and background service status", false,
                        if (notificationsGranted) "Granted" else "Not granted")
                    OutlinedButton(onClick = { openSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) { Text("Manage notifications") }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    PermissionStatusRow("Install app updates", "Allows APK updates initiated by TronDroid", false,
                        if (installAllowed) "Allowed" else "Not allowed")
                    OutlinedButton(onClick = { openSettings(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) { Text("Manage install access") }
                }
            }
        }
        item {
            Text("Background access", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PermissionStatusRow("Battery optimization", "Allow unrestricted background work for reliable sessions", false,
                        if (batteryAllowed) "Allowed" else "Restricted")
                    Text("This is special system access, not a runtime permission. Some Android brands may also require allowing background activity or auto-start in their own settings.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { openSettings(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.BatteryFull, null); Spacer(Modifier.width(8.dp)); Text("Open battery settings")
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
                modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Settings, null); Spacer(Modifier.width(8.dp)); Text("Open Android app settings") }
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val refreshTick = refresh
}

@Composable
private fun PermissionStatusRow(title: String, description: String, required: Boolean, status: String) {
    ListItem(headlineContent = { Text(title) },
        supportingContent = { Text(description + if (required) " · Required" else " · Optional") },
        trailingContent = {
            val granted = status == "Granted" || status == "Allowed" || status == "Declared"
            Surface(shape = RoundedCornerShape(50), color = if (granted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer) {
                Text(status, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer)
            }
        })
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
    var editingCredentialKey by remember { mutableStateOf<String?>(null) }
    var pendingCredentialDelete by remember { mutableStateOf<String?>(null) }
    val exportCredentialsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val json = org.json.JSONObject()
                    .put("format", "trondroid-credentials")
                    .put("schema_version", 1)
                    .put("telegram", org.json.JSONObject()
                        .put("api_id", bot.apiId)
                        .put("api_hash", apiHash))
                    .put("custom_credentials", org.json.JSONObject().apply {
                        customCredentials.forEach { (key, value) -> put(key, value) }
                    })
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json.toString(2)) }
                    ?: error("Unable to open the selected destination.")
            }
            android.widget.Toast.makeText(context,
                result.fold({ "Credentials exported." }, { "Export failed: ${it.message}" }),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val importCredentialsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to read the selected JSON file.")
                require(raw.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "JSON file is larger than 512 KB." }
                val root = org.json.JSONObject(raw)
                val telegram = root.optJSONObject("telegram")
                val json = if (root.optString("format") == "trondroid-credentials") {
                    require(root.optInt("schema_version") == 1) { "Unsupported credentials backup version." }
                    root.optJSONObject("custom_credentials") ?: org.json.JSONObject()
                } else root
                require(json.length() in 1..200) { "JSON must contain between 1 and 200 key/value entries." }
                val imported = buildMap {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next().trim()
                        val value = json.optString(key).trim()
                        require(key.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "Invalid credential key: $key" }
                        require(value.isNotBlank()) { "Credential value for $key is empty." }
                        put(key, value)
                    }
                }
                require(imported.size == json.length()) { "Duplicate or invalid credential entries." }
                telegram?.optString("api_hash")?.takeIf { it.isNotBlank() }?.let {
                    onSaveApiHash(it)
                    hash = it
                }
                onSaveCustomCredentials(customCredentials + imported)
            }
            android.widget.Toast.makeText(context,
                result.fold({ "Credentials imported." }, { "Import failed: ${it.message ?: "Invalid JSON"}" }),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { importCredentialsLauncher.launch(arrayOf("application/json", "text/json", "*/*")) }) {
                            Icon(Icons.Rounded.FileOpen, "Import credentials JSON")
                        }
                        IconButton(onClick = { exportCredentialsLauncher.launch("trondroid-credentials.json") }) {
                            Icon(Icons.Rounded.FileDownload, "Export credentials JSON")
                        }
                        FilledTonalButton(onClick = {
                            editingCredentialKey = null
                            showCustomCredentialsDialog = true
                        }) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add")
                        }
                    }

                }
                if (customCredentials.isEmpty()) {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Text("No custom credentials added.", Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        val entries = customCredentials.toSortedMap().entries.toList()
                        entries.forEachIndexed { index, entry ->
                            val shape = when {
                                entries.size == 1 -> RoundedCornerShape(18.dp)
                                index == 0 -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                                index == entries.lastIndex -> RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
                                else -> RoundedCornerShape(0.dp)
                            }
                            CustomCredentialField(
                                keyName = entry.key,
                                value = entry.value,
                                shape = shape,
                                onEdit = {
                                    editingCredentialKey = entry.key
                                    showCustomCredentialsDialog = true
                                },
                                onDelete = { pendingCredentialDelete = entry.key }
                            )
                        }
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
        val editingKey = editingCredentialKey
        CustomCredentialsDialog(
            existing = if (editingKey == null) customCredentials else mapOf(editingKey to customCredentials[editingKey].orEmpty()),
            onDismiss = {
                showCustomCredentialsDialog = false
                editingCredentialKey = null
            },
            onConfirm = { values ->
                val merged = customCredentials.toMutableMap()
                if (editingKey != null) merged.remove(editingKey)
                merged.putAll(values)
                onSaveCustomCredentials(merged)
                showCustomCredentialsDialog = false
                editingCredentialKey = null
            }
        )
    }
    pendingCredentialDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingCredentialDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete credential?") },
            text = { Text("Remove $key from this account? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveCustomCredentials(customCredentials - key)
                        pendingCredentialDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingCredentialDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CustomCredentialField(
    keyName: String,
    value: String,
    shape: RoundedCornerShape,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var reveal by remember(keyName) { mutableStateOf(false) }
    val sensitive = keyName.contains("TOKEN", true) || keyName.contains("HASH", true) ||
        keyName.contains("SECRET", true) || keyName.contains("PASSWORD", true)
    Card(shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(keyName, style = MaterialTheme.typography.labelLarge)
            Text(
                if (sensitive && !reveal) "••••••••••••••••" else value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (sensitive) {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide $keyName" else "Show $keyName")
                    }
                }
                IconButton(onClick = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText(keyName, value))
                }) { Icon(Icons.Rounded.ContentCopy, "Copy $keyName") }
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit $keyName") }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete $keyName", tint = MaterialTheme.colorScheme.error) }
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
