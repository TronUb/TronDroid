package com.vikram.trondroid

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import com.vikram.trondroid.data.BotStore
import com.vikram.trondroid.telegram.TelegramBackgroundService
import com.vikram.trondroid.telegram.TelegramRuntime
import com.vikram.trondroid.telegram.TelegramBotRuntime
import com.vikram.trondroid.ui.TronDroidHome
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = BotStore(applicationContext)
        val preferences = getSharedPreferences("trondroid.settings", MODE_PRIVATE)

        fun exportSettingsBackup(): String {
            val root = JSONObject()
                .put("format", "trondroid-settings-backup")
                .put("schema_version", 1)
            listOf("trondroid.settings", "trondroid.store", "trondroid.plugins").forEach { name ->
                val values = getSharedPreferences(name, MODE_PRIVATE).all
                val section = JSONObject()
                values.forEach { (key, value) ->
                    when (value) {
                        is Set<*> -> section.put(key, JSONArray(value.filterIsInstance<String>()))
                        null -> Unit
                        else -> section.put(key, value)
                    }
                }
                root.put(name, section)
            }
            return root.toString(2)
        }

        fun importSettingsBackup(raw: String) {
            val root = JSONObject(raw)
            require(root.optString("format") == "trondroid-settings-backup") {
                "This is not a TronDroid settings backup."
            }
            require(root.optInt("schema_version") == 1) { "Unsupported backup version." }
            listOf("trondroid.settings", "trondroid.store", "trondroid.plugins").forEach { name ->
                val section = root.optJSONObject(name) ?: return@forEach
                val editor = getSharedPreferences(name, MODE_PRIVATE).edit().clear()
                val keys = section.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = section.get(key)) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is JSONArray -> editor.putStringSet(key, buildSet {
                            for (index in 0 until value.length()) add(value.optString(index))
                        })
                        JSONObject.NULL -> Unit
                        else -> editor.putString(key, value.toString())
                    }
                }
                check(editor.commit()) { "Unable to restore $name preferences." }
            }
        }

        setContent {
            var bots by remember { mutableStateOf(store.all()) }
            var selectedId by remember { mutableStateOf(bots.firstOrNull()?.id) }
            var themeMode by remember { mutableStateOf(preferences.getString("theme_mode", "system") ?: "system") }
            var dynamicColors by remember { mutableStateOf(preferences.getBoolean("dynamic_colors", true)) }
            val engines by TelegramRuntime.engines.collectAsState()
            val botNames by TelegramBotRuntime.botNames.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> systemDark }
            val selected = bots.firstOrNull { it.id == selectedId }
            val engine = selected?.let { engines[it.id] }

            LaunchedEffect(Unit) {
                if (preferences.getBoolean("auto_start_sessions", true)) {
                    TelegramBackgroundService.reconcile(applicationContext)
                }
            }
            LaunchedEffect(botNames) { bots = store.all() }

            val colorScheme = when {
                dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(this@MainActivity) else dynamicLightColorScheme(this@MainActivity)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                SideEffect {
                    window.statusBarColor = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    window.navigationBarColor = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    val insets = WindowCompat.getInsetsController(window, window.decorView)
                    insets.isAppearanceLightStatusBars = !dark
                    insets.isAppearanceLightNavigationBars = !dark
                }
                TronDroidHome(
                    bots = bots,
                    selected = selected,
                    engine = engine,
                    themeMode = themeMode,
                    onThemeChange = {
                        themeMode = it
                        preferences.edit().putString("theme_mode", it).apply()
                    },
                    dynamicColors = dynamicColors,
                    onDynamicColorsChange = {
                        dynamicColors = it
                        preferences.edit().putBoolean("dynamic_colors", it).apply()
                    },
                    onSelect = { selectedId = it },
                    onRename = { id, name -> store.rename(id, name); bots = store.all() },
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
                    onUpdateApiHash = { id, value -> store.updateApiHash(id, value) },
                    onReadCustomCredentials = { store.customCredentials(it) },
                    onSaveCustomCredentials = { id, values ->
                        store.updateCustomCredentials(id, values)
                        bots = store.all()
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onBotToggle = { id, enabled ->
                        store.setBotEnabled(id, enabled)
                        bots = store.all()
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onSetAllConnections = { enabled ->
                        store.setAllEnabled(enabled)
                        bots = store.all()
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onDeleteAllConnections = {
                        // Stop workers before deleting their TDLib data directories.
                        com.vikram.trondroid.telegram.TelegramRuntime.stopAll()
                        TelegramBotRuntime.stopAll()
                        store.removeAll()
                        bots = store.all()
                        selectedId = null
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onSetAllConnections = { enabled ->
                        store.setAllEnabled(enabled)
                        bots = store.all()
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onDeleteAllConnections = {
                        store.removeAll()
                        bots = store.all()
                        selectedId = null
                        TelegramBackgroundService.reconcile(applicationContext)
                    },
                    onExportSettings = { exportSettingsBackup() },
                    onImportSettings = { raw ->
                        importSettingsBackup(raw)
                        bots = store.all()
                        selectedId = bots.firstOrNull()?.id
                        themeMode = preferences.getString("theme_mode", "system") ?: "system"
                        dynamicColors = preferences.getBoolean("dynamic_colors", true)
                        TelegramBackgroundService.reconcile(applicationContext)
                    }
                )
            }
        }
    }
}
