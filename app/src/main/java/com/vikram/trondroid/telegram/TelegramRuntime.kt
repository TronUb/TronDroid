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
        mutableEngines.value.filterKeys { it !in enabled }.values.forEach { it.close() }
        val next = mutableEngines.value.filterKeys { it in enabled }.toMutableMap()
        enabled.values.forEach { bot ->
            if (next[bot.id] == null) {
                val hash = store.apiHash(bot.id)
                if (hash.isNullOrBlank()) return@forEach
                val engine = TelegramEngine(app, bot, hash)
                next[bot.id] = engine
                engine.start()
            }
        }
        mutableEngines.value = next
    }

    fun engine(id: String?): TelegramEngine? = id?.let { mutableEngines.value[it] }

    @Synchronized
    fun stopAll() {
        mutableEngines.value.values.forEach { it.close() }
        mutableEngines.value = emptyMap()
    }

    fun activeCount(): Int = mutableEngines.value.size
}
