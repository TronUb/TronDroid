package com.vikram.trondroid.telegram

import android.content.Context
import android.util.Log
import com.vikram.trondroid.data.BotProfile
import com.vikram.trondroid.automation.TelegramUpdateHub
import com.vikram.trondroid.automation.AutomationDispatcher
import com.vikram.trondroid.automation.AutomationMessage
import com.vikram.trondroid.automation.AutomationPlugin
import com.vikram.trondroid.automation.PingAutomationPlugin
import com.vikram.trondroid.automation.WikipediaAutomationPlugin
import com.vikram.trondroid.automation.ImdbAutomationPlugin
import com.vikram.trondroid.automation.RedditAutomationPlugin
import com.vikram.trondroid.automation.TranslateAutomationPlugin
import com.vikram.trondroid.automation.TimeAutomationPlugin
import com.vikram.trondroid.automation.AliveAutomationPlugin
import com.vikram.trondroid.automation.AnimeAutomationPlugin
import com.vikram.trondroid.automation.FunAutomationPlugin
import com.vikram.trondroid.automation.HelpAutomationPlugin
import com.vikram.trondroid.automation.AfkAutomationPlugin
import com.vikram.trondroid.automation.NotesAutomationPlugin
import com.vikram.trondroid.automation.WeatherAutomationPlugin
import com.vikram.trondroid.automation.CurrencyAutomationPlugin
import com.vikram.trondroid.automation.TemperatureAutomationPlugin
import com.vikram.trondroid.automation.PasteAutomationPlugin
import com.vikram.trondroid.automation.ProfileAutomationPlugin
import com.vikram.trondroid.automation.ChatInfoAutomationPlugin
import com.vikram.trondroid.automation.GroupInfoAutomationPlugin
import com.vikram.trondroid.automation.QrAutomationPlugin
import com.vikram.trondroid.automation.PurgeAutomationPlugin
import com.vikram.trondroid.automation.StatsAutomationPlugin
import com.vikram.trondroid.automation.SpamAutomationPlugin
import com.vikram.trondroid.automation.DigitalVaultAutomationPlugin
import com.vikram.trondroid.automation.TextToolsAutomationPlugin
import com.vikram.trondroid.automation.WelcomeAutomationPlugin
import com.vikram.trondroid.automation.AdminAutomationPlugin
import com.vikram.trondroid.automation.BroadcastAutomationPlugin
import com.vikram.trondroid.automation.FileToolsAutomationPlugin
import com.vikram.trondroid.automation.WebMediaAutomationPlugin
import com.vikram.trondroid.automation.UtilityExtrasAutomationPlugin
import com.vikram.trondroid.automation.PmPermitAutomationPlugin
import com.vikram.trondroid.plugins.PluginSettings
import com.vikram.trondroid.plugins.ExternalPluginLoader
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EngineLog(val time: String, val message: String, val isError: Boolean = false)
data class TelegramAccountInfo(
    val id: Long,
    val name: String,
    val username: String,
    val phone: String,
    val isPremium: Boolean,
    val isVerified: Boolean,
    val photoPath: String? = null
)

class TelegramEngine(private val context: Context, private val profile: BotProfile, private val apiHash: String) : Client.ResultHandler {
    companion object { init { System.loadLibrary("tdjni") } }
    private var client: Client? = null
    private val _authorization = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authorization: StateFlow<TdApi.AuthorizationState?> = _authorization.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _chats = MutableStateFlow<List<TdApi.Chat>>(emptyList())
    val chats: StateFlow<List<TdApi.Chat>> = _chats.asStateFlow()
    private val _account = MutableStateFlow<TelegramAccountInfo?>(null)
    val account: StateFlow<TelegramAccountInfo?> = _account.asStateFlow()
    private val _logs = MutableStateFlow<List<EngineLog>>(emptyList())
    val logs: StateFlow<List<EngineLog>> = _logs.asStateFlow()
    private val pluginSettings = PluginSettings(context)
    val updateStore = TelegramUpdateStore()
    private val telegramGateway = TelegramClientGateway({ client }, updateStore, context.cacheDir) { _account.value?.id }
    private val automation = AutomationDispatcher(
        commandPrefix = { pluginSettings.handler() },
        isPluginEnabled = { pluginSettings.enabled(it) },
        sendText = { chatId, text ->
            val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
            client?.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { response ->
                if (response.constructor == TdApi.Error.CONSTRUCTOR) {
                    val error = response as TdApi.Error
                    log("Automation send failed: ${error.message}", true)
                } else log("Automation response sent to chat $chatId")
            }
        },
        editText = { chatId, messageId, text ->
            val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
            client?.send(TdApi.EditMessageText(chatId, messageId, null, content)) { response ->
                if (response.constructor == TdApi.Error.CONSTRUCTOR) {
                    val error = response as TdApi.Error
                    log("Command edit failed: ${error.message}", true)
                } else log("Edited command message $messageId")
            }
        },
        logger = { message, error -> log(message, error) },
        telegramGateway = { telegramGateway }
    )
    /** Shared TDLib update stream used by every native and external plugin. */
    val updateHub = TelegramUpdateHub { message, isError -> log(message, isError) }
    private val updateSubscription = updateHub.subscribe { update ->
        // Queue update callbacks before message handlers on the same automation executor.
        automation.dispatchUpdate(update)
        if (update is TdApi.UpdateNewMessage) dispatchNewMessage(update.message)
    }

    init {
        val nativePlugins = mutableListOf<AutomationPlugin>()
        nativePlugins += PingAutomationPlugin()
        nativePlugins += WikipediaAutomationPlugin()
        nativePlugins += ImdbAutomationPlugin()
        nativePlugins += RedditAutomationPlugin()
        nativePlugins += TranslateAutomationPlugin()
        nativePlugins += TimeAutomationPlugin()
        nativePlugins += AliveAutomationPlugin { profile.name }
        nativePlugins += AnimeAutomationPlugin()
        nativePlugins += FunAutomationPlugin()
        nativePlugins += HelpAutomationPlugin({ pluginSettings.handler() }) { nativePlugins }
        nativePlugins += AfkAutomationPlugin({ pluginSettings.handler() }, { _account.value?.id }) { _account.value?.username }
        nativePlugins += NotesAutomationPlugin(context.getSharedPreferences("trondroid.userbot.notes", Context.MODE_PRIVATE))
        nativePlugins += ProfileAutomationPlugin()
        nativePlugins += ChatInfoAutomationPlugin()
        nativePlugins += GroupInfoAutomationPlugin()
        nativePlugins += PurgeAutomationPlugin()
        nativePlugins += StatsAutomationPlugin()
        nativePlugins += SpamAutomationPlugin()
        nativePlugins += DigitalVaultAutomationPlugin(context.getSharedPreferences("trondroid.userbot.digital_vault", Context.MODE_PRIVATE))
        nativePlugins += TextToolsAutomationPlugin()
        nativePlugins += WelcomeAutomationPlugin(context.getSharedPreferences("trondroid.userbot.welcome", Context.MODE_PRIVATE))
        nativePlugins += AdminAutomationPlugin()
        nativePlugins += BroadcastAutomationPlugin()
        nativePlugins += FileToolsAutomationPlugin(context)
        nativePlugins += WebMediaAutomationPlugin()
        nativePlugins += UtilityExtrasAutomationPlugin()
        nativePlugins += PmPermitAutomationPlugin(context.getSharedPreferences("trondroid.userbot.pmpermit", Context.MODE_PRIVATE))
        nativePlugins += WeatherAutomationPlugin()
        nativePlugins += CurrencyAutomationPlugin()
        nativePlugins += TemperatureAutomationPlugin()
        nativePlugins += PasteAutomationPlugin()
        nativePlugins += QrAutomationPlugin()
        nativePlugins.forEach(automation::register)
        log("Loaded ${nativePlugins.size} native userbot plugins")
    }

    fun registerAutomationPlugin(plugin: AutomationPlugin) {
        automation.register(plugin)
        log("Automation plugin registered: ${plugin.id}")
    }

    fun loadExternalPlugins(uri: Uri): Int {
        val loaded = ExternalPluginLoader.loadFromUri(context, uri).filterIsInstance<AutomationPlugin>()
        require(loaded.isNotEmpty()) { "No AutomationPlugin implementation was found in this file." }
        loaded.forEach(automation::register)
        log("Loaded ${loaded.size} external userbot plugin(s)")
        return loaded.size
    }

    fun unregisterAutomationPlugin(pluginId: String) {
        automation.unregister(pluginId)
        log("Automation plugin unloaded: $pluginId")
    }

    private fun log(message: String, error: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logs.value = (_logs.value + EngineLog(time, message, error)).takeLast(200)
    }
    fun start() {
        if (client != null) return
        log("Initializing Telegram session for ${profile.name}")
        runCatching {
            Client.execute(TdApi.SetLogVerbosityLevel(0))
            client = Client.create(this, null, null)
        }.onFailure { _error.value = it.message ?: "Unable to initialize TDLib"; log(_error.value!!, true) }
    }
    override fun onResult(result: TdApi.Object) {
        // TDLib is the single source of truth: forward every Update subtype to the
        // shared plugin pipeline, not just new text messages.
        if (result is TdApi.Update) {
            updateStore.accept(result)
            updateHub.publish(result)
        }
        when (result.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> onAuthorizationState((result as TdApi.UpdateAuthorizationState).authorizationState)
            TdApi.User.CONSTRUCTOR -> updateAccount(result as TdApi.User)
            TdApi.File.CONSTRUCTOR -> {
                val file = result as TdApi.File
                updateStore.rememberFile(file)
                val current = _account.value
                if (current != null && file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
                    _account.value = current.copy(photoPath = file.local.path)
                }
            }
            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val chat = (result as TdApi.UpdateNewChat).chat
                updateStore.rememberChat(chat)
                _chats.value = (_chats.value.filterNot { it.id == chat.id } + chat).sortedBy { it.title.lowercase() }
            }
            TdApi.Error.CONSTRUCTOR -> {
                val e = result as TdApi.Error
                _error.value = e.message
                Log.e("TronDroid", "TDLib error " + e.code + ": " + e.message)
                log("TDLib error ${e.code}: ${e.message}", true)
            }
        }
    }
    private fun dispatchNewMessage(message: TdApi.Message) {
        val text = (message.content as? TdApi.MessageText)?.text?.text.orEmpty()
        val direction = if (message.isOutgoing) "OUTGOING →" else "INCOMING ←"
        val preview = text.ifBlank { "[${message.content.javaClass.simpleName}]" }.replace("\n", " ").take(180)
        val chatName = _chats.value.firstOrNull { it.id == message.chatId }?.title ?: "chat ${message.chatId}"
        log("$direction $chatName: $preview")
        // Deliver media-only messages too; plugins such as AFK and download
        // handlers need the original TDLib message even when it has no text.
        automation.dispatch(
            AutomationMessage(
                chatId = message.chatId,
                messageId = message.id,
                text = text,
                isOutgoing = message.isOutgoing,
                senderId = (message.senderId as? TdApi.MessageSenderUser)?.userId?.toString(),
                tdMessage = message
            )
        )
    }

    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        _authorization.value = state
        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                log("Configuring TDLib database and API credentials")
                client?.send(TdApi.SetTdlibParameters(false, context.filesDir.resolve("tdlib/" + profile.id).absolutePath,
                    null, null, true, true, true, true, profile.apiId, apiHash, "en",
                    android.os.Build.MODEL ?: "Android", android.os.Build.VERSION.RELEASE ?: "Android", "1.0.0"), this)
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> log("Waiting for phone number")
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> log("Telegram verification code required")
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> log("Two-step verification password required")
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> { log("Telegram account connected"); loadChats(); loadAccount() }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> { log("Telegram session closed"); client = null }
        }
    }
    fun submitPhone(phone: String) { log("Submitting phone number"); client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), this) }
    fun submitCode(code: String) { log("Submitting login code"); client?.send(TdApi.CheckAuthenticationCode(code), this) }
    fun submitPassword(password: String) { log("Submitting two-step verification password"); client?.send(TdApi.CheckAuthenticationPassword(password), this) }
    fun logout() { log("Logging out"); client?.send(TdApi.LogOut()) { r ->
        if (r.constructor == TdApi.Error.CONSTRUCTOR) { _error.value = (r as TdApi.Error).message; log(_error.value!!, true) }
    } }
    fun close() {
        val active = client
        if (active != null) {
            log("Closing Telegram session")
            active.send(TdApi.Close()) {}
            client = null
        }
        // Tear down plugin infrastructure even if TDLib already transitioned
        // to AuthorizationStateClosed or failed during initialization.
        updateSubscription.close()
        updateHub.close()
        updateStore.clear()
        telegramGateway.shutdown()
        automation.shutdown()
    }
    private fun loadAccount() {
        client?.send(TdApi.GetMe()) { result ->
            if (result is TdApi.User) updateAccount(result)
            else if (result is TdApi.Error) log("Unable to load Telegram profile: " + result.message, true)
        }
    }

    private fun updateAccount(user: TdApi.User) {
        updateStore.rememberUser(user)
        val username = user.usernames?.activeUsernames?.firstOrNull().orEmpty()
        val name = listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
        _account.value = TelegramAccountInfo(user.id, name, username, user.phoneNumber, user.isPremium, false)
        user.profilePhoto?.small?.let { photo ->
            client?.send(TdApi.DownloadFile(photo.id, 32, 0, 0, true)) { fileResult ->
                if (fileResult is TdApi.File && fileResult.local.isDownloadingCompleted && fileResult.local.path.isNotBlank()) {
                    _account.value = _account.value?.copy(photoPath = fileResult.local.path)
                }
            }
        }
    }

    private fun loadChats() {
        log("Loading recent chats")
        client?.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { r ->
            if (r.constructor == TdApi.Error.CONSTRUCTOR) { _error.value = (r as TdApi.Error).message; log(_error.value!!, true) }
            else client?.send(TdApi.GetChats(TdApi.ChatListMain(), 100)) { result ->
                if (result is TdApi.Chats) result.chatIds.forEach { id ->
                    client?.send(TdApi.GetChat(id)) { chatResult ->
                        if (chatResult is TdApi.Chat) {
                            updateStore.rememberChat(chatResult)
                            _chats.value =
                                (_chats.value.filterNot { it.id == chatResult.id } + chatResult).sortedBy { it.title.lowercase() }
                        }
                    }
                }
            }
        }
    }
}
