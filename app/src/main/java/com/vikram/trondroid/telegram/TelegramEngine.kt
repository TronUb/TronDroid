package com.vikram.trondroid.telegram

import android.content.Context
import android.util.Log
import com.vikram.trondroid.data.BotProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TelegramEngine(private val context: Context, private val profile: BotProfile, private val apiHash: String) : Client.ResultHandler {
    companion object { init { System.loadLibrary("tdjni") } }
    private var client: Client? = null
    private val _authorization = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authorization: StateFlow<TdApi.AuthorizationState?> = _authorization.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _chats = MutableStateFlow<List<TdApi.Chat>>(emptyList())
    val chats: StateFlow<List<TdApi.Chat>> = _chats.asStateFlow()

    fun start() {
        if (client != null) return
        runCatching {
            Client.execute(TdApi.SetLogVerbosityLevel(0))
            client = Client.create(this, null, null)
        }.onFailure { _error.value = it.message ?: "Unable to initialize TDLib" }
    }
    override fun onResult(result: TdApi.Object) {
        when (result.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR ->
                onAuthorizationState((result as TdApi.UpdateAuthorizationState).authorizationState)
            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val chat = (result as TdApi.UpdateNewChat).chat
                _chats.value = (_chats.value.filterNot { it.id == chat.id } + chat).sortedBy { it.title.lowercase() }
            }
            TdApi.Error.CONSTRUCTOR -> {
                val e = result as TdApi.Error
                _error.value = e.message
                Log.e("TronDroid", "TDLib error " + e.code + ": " + e.message)
            }
        }
    }
    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        _authorization.value = state
        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> client?.send(TdApi.SetTdlibParameters(
                false, context.filesDir.resolve("tdlib/" + profile.id).absolutePath,
                null, null, true, true, true, false, profile.apiId, apiHash,
                "en", android.os.Build.MODEL ?: "Android",
                android.os.Build.VERSION.RELEASE ?: "Android", "1.0.0"
            ), this)
            TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR ->
                client?.send(TdApi.CheckDatabaseEncryptionKey(""), this)
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> loadChats()
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> client = null
        }
    }
    fun submitPhone(phone: String) { client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), this) }
    fun submitCode(code: String) { client?.send(TdApi.CheckAuthenticationCode(code), this) }
    fun submitPassword(password: String) { client?.send(TdApi.CheckAuthenticationPassword(password), this) }
    fun logout() { client?.send(TdApi.LogOut()) { r ->
        if (r.constructor == TdApi.Error.CONSTRUCTOR) _error.value = (r as TdApi.Error).message
    } }
    fun close() { client?.send(TdApi.Close()) {}; client = null }
    private fun loadChats() {
        client?.send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { r ->
            if (r.constructor == TdApi.Error.CONSTRUCTOR) _error.value = (r as TdApi.Error).message
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
