package com.vikram.trondroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import com.vikram.trondroid.data.BotStore
import com.vikram.trondroid.telegram.TelegramBackgroundService
import com.vikram.trondroid.telegram.TelegramRuntime
import com.vikram.trondroid.ui.TronDroidHome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = BotStore(applicationContext)
        val preferences = getSharedPreferences("trondroid.settings", MODE_PRIVATE)
        setContent {
            var bots by remember { mutableStateOf(store.all()) }
            var selectedId by remember { mutableStateOf(bots.firstOrNull()?.id) }
            var themeMode by remember { mutableStateOf(preferences.getString("theme_mode", "system") ?: "system") }
            val engines by TelegramRuntime.engines.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> systemDark }
            val selected = bots.firstOrNull { it.id == selectedId }
            val engine = selected?.let { engines[it.id] }

            LaunchedEffect(Unit) { TelegramBackgroundService.reconcile(applicationContext) }

            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                TronDroidHome(
                    bots = bots,
                    selected = selected,
                    engine = engine,
                    themeMode = themeMode,
                    onThemeChange = {
                        themeMode = it
                        preferences.edit().putString("theme_mode", it).apply()
                    },
                    onSelect = { selectedId = it },
                    onAdd = { name, phone, id, hash ->
                        val added = store.add(name, phone, id, hash)
                        bots = store.all()
                        selectedId = added.id
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onRemove = { id ->
                        store.remove(id)
                        bots = store.all()
                        selectedId = bots.firstOrNull()?.id
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onToggle = { id, enabled ->
                        store.setEnabled(id, enabled)
                        bots = store.all()
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onReadApiHash = { store.apiHash(it) },
                    onUpdateApiHash = { id, value -> store.updateApiHash(id, value) }
                )
            }
        }
    }
}
