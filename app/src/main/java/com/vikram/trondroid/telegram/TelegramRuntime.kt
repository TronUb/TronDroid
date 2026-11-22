package com.vikram.trondroid.telegram

import android.content.Context
import com.vikram.trondroid.data.BotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TelegramRuntime {
    private val mutableEngines = MutableStateFlow<Map<String, TelegramEngine>>(emptyMap())
    val engines: StateFlow<Map<String, TelegramEngine>> = mutableEngines.asStateFlow()

    @Synchronized
    fun reconcile(context: Context) {
        val app = context.applicationContext
        val store = BotStore(app)
        val enabled = store.all().filter { it.enabled }.associateBy { it.id }
        val current = mutableEngines.value
        val stale = current.filterKeys { it !in enabled }
        val next = current.filterKeys { it in enabled }.toMutableMap()

        // Publish the new engine set before closing old engines. Compose screens
        // immediately stop observing a session that has just been disabled.
        mutableEngines.value = next
        stale.values.forEach { engine ->
            runCatching { engine.close() }
                .onFailure { android.util.Log.w("TronDroidTelegram", "Disabled session cleanup failed", it) }
        }

        enabled.values.forEach { bot ->
            if (next[bot.id] == null) {
                val hash = store.apiHash(bot.id)?.takeIf { it.isNotBlank() } ?: return@forEach
                runCatching {
                    val engine = TelegramEngine(app, bot, hash)
                    next[bot.id] = engine
                    engine.start()
                }.onFailure {
                    android.util.Log.e("TronDroidTelegram", "Unable to start session ${bot.id}", it)
                }
            }
        }
        mutableEngines.value = next.toMap()
    }

    /** Recreate one enabled userbot engine while leaving other accounts untouched. */
    @Synchronized
    fun restart(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val store = BotStore(app)
        val profile = store.all().firstOrNull { it.id == id && it.enabled } ?: return false
        val hash = store.apiHash(id)?.takeIf { it.isNotBlank() } ?: return false
        val current = mutableEngines.value[id] ?: return false
        current.close()
        val replacement = TelegramEngine(app, profile, hash)
        mutableEngines.value = mutableEngines.value + (id to replacement)
        replacement.start()
        return true
    }

    fun engine(id: String?): TelegramEngine? = id?.let { mutableEngines.value[it] }

    @Synchronized
    fun stopAll() {
        mutableEngines.value.values.forEach { it.close() }
        mutableEngines.value = emptyMap()
    }

    fun activeCount(): Int = mutableEngines.value.size
}
