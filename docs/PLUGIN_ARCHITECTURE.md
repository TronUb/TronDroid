# TronDroid plugin architecture

TronDroid plugins are native Kotlin implementations running on the authenticated TDLib session. Python/Pyrogram modules in `TronUb/Tron` are the behavioral reference; they are not executed by the Android app.

## Runtime flow

1. `TelegramEngine` owns one TDLib `Client` per configured userbot profile.
2. Every incoming `TdApi.Update` is passed to `TelegramUpdateStore` and `TelegramUpdateHub`.
3. `TelegramUpdateHub` serializes update delivery and isolates listener failures.
4. The engine's subscription forwards updates to `AutomationDispatcher.dispatchUpdate`.
5. Enabled plugins receive `AutomationUpdateContext` through `onTelegramUpdate`.
6. New-message updates also enter `AutomationDispatcher.dispatch`; outgoing commands are parsed and routed to the first enabled plugin that owns the command.
7. Plugin TDLib requests go through `TelegramClientGateway`, which shares the engine's authenticated client.

## State available to plugins

- `telegram.updates`: bounded journal of the latest 500 raw TDLib updates.
- `telegram.messages`: in-memory cache keyed by `chatId to messageId`, bounded to 2,000 messages.
- `telegram.chats`, `telegram.users`, `telegram.files`: latest cached TDLib entities.
- `telegram.userStatuses`: latest known status per user.
- `telegram.repliedMessage(message)`: resolves a reply from the in-memory message cache.
- `telegram.send(function) { result -> ... }`: execute a TDLib request on the existing session. The callback receives `TdApi.Object`; always check for `TdApi.Error` before casting.
- `telegram.sendPhotoUrl(...)` / `sendAnimationUrl(...)`: download bounded HTTPS media into the app cache and upload it through TDLib.
- `telegram.sendToSavedMessages(text)`: send text to the authenticated account’s Saved Messages chat.

TDLib remains the durable source of truth. Plugins should query TDLib through the gateway when an entity is not present in the bounded cache; they must not create a second TDLib client.

## Implementing a plugin

```kotlin
class ExamplePlugin : AutomationPlugin {
    override val id = "userbot:example"

    override val commands = listOf(
        AutomationCommand("example", "Describe the action.", "example <text>")
    )

    override fun onCommand(context: AutomationContext) {
        val value = context.arguments.joinToString(" ")
        if (value.isBlank()) return context.reply("Usage: .example <text>")
        context.reply("Processed: $value")
    }

    override fun onTelegramUpdate(context: AutomationUpdateContext) {
        when (val update = context.update) {
            is TdApi.UpdateUserStatus -> {
                // React to user status updates here.
            }
        }
    }
}
```

Register native plugins in `TelegramEngine.init`. Keep plugin IDs stable because enable/disable preferences are keyed by ID. Keep network and CPU-heavy work off the TDLib callback thread; plugin callbacks are dispatched serially by the automation executor.

## Porting requirements

A port is complete only when its source behavior, aliases, reply/media handling, permissions, persistent state, and relevant update handlers are implemented and verified. A catalog entry or a command stub alone does not count as a completed port. Features that depend on desktop/server-only facilities must be redesigned for Android or explicitly remain unsupported.
