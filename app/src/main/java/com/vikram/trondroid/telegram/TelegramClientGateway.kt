package com.vikram.trondroid.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * The only supported way for plugins to issue TDLib requests.
 * Plugins share the account's authenticated client; they must never create
 * another TDLib Client or open a second Telegram session.
 */
class TelegramClientGateway(
    private val clientProvider: () -> Client?
) {
    fun <T : TdApi.Object> send(
        function: TdApi.Function<T>,
        callback: (T) -> Unit
    ) {
        val activeClient = clientProvider() ?: return
        activeClient.send(function) { result ->
            @Suppress("UNCHECKED_CAST")
            callback(result as T)
        }
    }

    fun isConnected(): Boolean = clientProvider() != null
}
