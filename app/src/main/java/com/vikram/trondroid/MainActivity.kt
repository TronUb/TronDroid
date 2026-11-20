package com.vikram.trondroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import com.vikram.trondroid.data.BotStore
import com.vikram.trondroid.telegram.TelegramEngine
import com.vikram.trondroid.ui.TronDroidHome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = BotStore(applicationContext)
        setContent {
            var bots by remember { mutableStateOf(store.all()) }
            var selectedId by remember { mutableStateOf(bots.firstOrNull()?.id) }
            val selected = bots.firstOrNull { it.id == selectedId }
            val engine = remember(selected?.id) {
                selected?.let { p -> store.apiHash(p.id)?.let { TelegramEngine(applicationContext, p, it) } }
            }
            LaunchedEffect(engine) { engine?.start() }
            DisposableEffect(engine) { onDispose { engine?.close() } }
            MaterialTheme(colorScheme = darkColorScheme()) {
                TronDroidHome(bots, selected, engine,
                    onSelect = { selectedId = it },
                    onAdd = { name, phone, id, hash ->
                        val added = store.add(name, phone, id, hash)
                        bots = store.all(); selectedId = added.id
                    },
                    onRemove = { id ->
                        store.remove(id); bots = store.all(); selectedId = bots.firstOrNull()?.id
                    })
            }
        }
    }
}
