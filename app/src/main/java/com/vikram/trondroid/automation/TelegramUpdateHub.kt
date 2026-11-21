package com.vikram.trondroid.automation

import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single ingress for TDLib updates. TelegramEngine forwards every TdApi.Update here;
 * plugins subscribe to typed update callbacks instead of polling Telegram or creating
 * their own TDLib clients.
 *
 * Delivery is serialized to preserve Telegram update order. A failing subscriber cannot
 * prevent other subscribers from receiving the same update.
 */
class TelegramUpdateHub(
    private val logger: (String, Boolean) -> Unit
) {
    fun interface Listener {
        fun onUpdate(update: TdApi.Update)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TronDroid-TelegramUpdates").apply { isDaemon = true }
    }

    fun subscribe(listener: Listener): AutoCloseable {
        check(!closed.get()) { "Telegram update hub is closed" }
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun publish(update: TdApi.Update) {
        if (closed.get()) return
        executor.execute {
            listeners.forEach { listener ->
                runCatching { listener.onUpdate(update) }
                    .onFailure { logger("Telegram update listener failed: ${it.message}", true) }
            }
        }
    }

    fun subscriberCount(): Int = listeners.size

    fun close() {
        if (closed.compareAndSet(false, true)) {
            listeners.clear()
            executor.shutdownNow()
        }
    }
}
