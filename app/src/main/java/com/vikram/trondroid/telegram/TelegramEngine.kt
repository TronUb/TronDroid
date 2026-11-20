package com.vikram.trondroid.telegram

import android.content.Context
import android.util.Log
import com.vikram.trondroid.data.BotProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EngineLog(val time: String, val message: String, val isError: Boolean = false)

class TelegramEngine(private val context: Context, private val profile: BotProfile, private val apiHash: String) : Client.ResultHandler {
    companion object { init { System.loadLibrary("tdjni") } }
    private var client: Client? = null
    private val _authorization = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authorization: StateFlow<TdApi.AuthorizationState?> = _authorization.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _chats = MutableStateFlow<List<TdApi.Chat>>(emptyList())
    val chats: StateFlow<List<TdApi.Chat>> = _chats.asStateFlow()
    private val _logs = MutableStateFlow<List<EngineLog>>(emptyList())
    val logs: StateFlow<List<EngineLog>> = _logs.asStateFlow()

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
        when (result.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> onAuthorizationState((result as TdApi.UpdateAuthorizationState).authorizationState)
            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val chat = (result as TdApi.UpdateNewChat).chat
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
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> { log("Telegram account connected"); loadChats() }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> { log("Telegram session closed"); client = null }
        }
    }
    fun submitPhone(phone: String) { log("Submitting phone number"); client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), this) }
    fun submitCode(code: String) { log("Submitting login code"); client?.send(TdApi.CheckAuthenticationCode(code), this) }
    fun submitPassword(password: String) { log("Submitting two-step verification password"); client?.send(TdApi.CheckAuthenticationPassword(password), this) }
    fun logout() { log("Logging out"); client?.send(TdApi.LogOut()) { r ->
        if (r.constructor == TdApi.Error.CONSTRUCTOR) { _error.value = (r as TdApi.Error).message; log(_error.value!!, true) }
    } }
    fun close() { client?.send(TdApi.Close()) {}; client = null }
    private fun loadChats() {
        log("Loading recent chats")
        client?.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { r ->
            if (r.constructor == TdApi.Error.CONSTRUCTOR) { _error.value = (r as TdApi.Error).message; log(_error.value!!, true) }
            else client?.send(TdApi.GetChats(TdApi.ChatListMain(), 100)) { result ->
                if (result is TdApi.Chats) result.chatIds.forEach { id ->
                    client?.send(TdApi.GetChat(id)) { chatResult ->
                        if (chatResult is TdApi.Chat) _chats.value =
                            (_chats.value.filterNot { it.id == chatResult.id } + chatResult).sortedBy { it.title.lowercase() }
                    }
                }
            }
        }
    }
}
