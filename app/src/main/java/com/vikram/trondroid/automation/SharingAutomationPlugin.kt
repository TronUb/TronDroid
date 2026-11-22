package com.vikram.trondroid.automation

import android.content.Context
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shares portable, declarative plugin manifests.
 *
 * Android cannot safely execute imported Python source. Imported manifests are stored
 * as metadata/command documentation only; they never execute code or gain app permissions.
 */
class SharingAutomationPlugin(private val context: Context) : AutomationPlugin {
    override val id = "userbot:share"
    private val directory = File(context.filesDir, "shared-plugin-manifests").apply { mkdirs() }

    override val commands = listOf(
        AutomationCommand("send", "Export a built-in plugin's portable manifest as a Telegram document.", "send <plugin id or short name>"),
        AutomationCommand("install", "Import a .tdplugin manifest by replying to its document.", "install (reply to .tdplugin file)"),
        AutomationCommand("uninstall", "Remove an imported plugin manifest by ID.", "uninstall <plugin id>"),
        AutomationCommand("sharedplugins", "List imported plugin manifests.", "sharedplugins")
    )

    override fun onCommand(context: AutomationContext) {
        when (context.command) {
            "send" -> export(context)
            "install" -> install(context)
            "uninstall" -> uninstall(context)
            "sharedplugins" -> list(context)
        }
    }

    private fun export(context: AutomationContext) {
        val requested = context.arguments.joinToString(" ").trim()
            .removeSuffix(".py").removeSuffix(".tdplugin")
        if (requested.isBlank()) return context.reply("Usage: .send <plugin id or short name> (example: .send userbot:stickers)")
        val plugin = PluginCatalog.find(if (requested.contains(":")) requested else "userbot:$requested")
            ?: return context.reply("Plugin not found in the built-in catalog: $requested")
        val manifest = JSONObject()
            .put("format", "trondroid-plugin-manifest")
            .put("schema_version", 1)
            .put("id", plugin.id)
            .put("name", plugin.title)
            .put("version", "1.0")
            .put("description", plugin.summary)
            .put("category", plugin.category)
            .put("commands", JSONArray().apply {
                plugin.commands.forEach { command ->
                    put(JSONObject()
                        .put("name", command.name)
                        .put("usage", command.usage)
                        .put("description", command.description)
                        .put("example", command.example))
                }
            })
        val safeName = plugin.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.cacheDir, "$safeName.tdplugin")
        runCatching { file.writeText(manifest.toString(2)) }
            .onFailure { return context.reply("Unable to export plugin manifest: ${it.message}") }
        val gateway = context.telegram ?: run {
            file.delete()
            return context.reply("Telegram session is unavailable.")
        }
        gateway.sendDocumentPath(context.message.chatId, file.absolutePath, "TronDroid plugin manifest • ${plugin.title}") { result ->
            context.reply(if (result is TdApi.Error) "Plugin export failed: ${result.message}" else "Exported ${plugin.title} manifest. This package contains metadata and usage documentation, not executable code.")
            file.delete()
        }
    }

    private fun install(context: AutomationContext) {
        val gateway = context.telegram ?: return context.reply("Telegram session is unavailable.")
        val replied = context.message.tdMessage?.let(gateway::repliedMessage)
            ?: return context.reply("Reply to a .tdplugin manifest document with .install")
        val document = (replied.content as? TdApi.MessageDocument)?.document
            ?: return context.reply("The replied message must contain a .tdplugin document.")
        val fileName = document.fileName.lowercase()
        if (!fileName.endsWith(".tdplugin")) return context.reply("Only .tdplugin manifest files are accepted. Executable .py, .dex, .apk and .jar files are not installed.")
        context.reply("Validating plugin manifest…")
        gateway.downloadFile(document.document.id) { file, error ->
            if (file == null || error != null) {
                context.reply("Manifest download failed: ${error ?: "unknown error"}. Retry once Telegram finishes downloading it.")
                return@downloadFile
            }
            runCatching {
                val source = File(file.local.path)
                require(source.length() in 1..128L * 1024) { "Manifest must be between 1 byte and 128 KB." }
                val json = JSONObject(source.readText())
                require(json.optString("format") == "trondroid-plugin-manifest") { "Unsupported manifest format." }
                require(json.optInt("schema_version") == 1) { "Unsupported manifest version." }
                val id = json.optString("id")
                require(id.matches(Regex("[A-Za-z0-9:_-]{3,80}"))) { "Invalid plugin ID." }
                val name = json.optString("name").take(80)
                require(name.isNotBlank()) { "Plugin name is missing." }
                val commands = json.optJSONArray("commands") ?: JSONArray()
                require(commands.length() in 0..32) { "A manifest may contain up to 32 commands." }
                for (index in 0 until commands.length()) {
                    val command = commands.getJSONObject(index)
                    require(command.optString("name").matches(Regex("[A-Za-z0-9_]{1,32}"))) { "Invalid command name in manifest." }
                }
                val destination = File(directory, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
                destination.writeText(json.toString(2))
                "Imported manifest for $name (${commands.length()} documented commands). It is saved locally as metadata only; imported code is never executed."
            }.onSuccess(context.reply).onFailure { context.reply("Plugin manifest rejected: ${it.message ?: "invalid file"}") }
        }
    }

    private fun uninstall(context: AutomationContext) {
        val id = context.arguments.firstOrNull()?.trim().orEmpty()
        if (id.isBlank()) return context.reply("Usage: .uninstall <plugin id>")
        val file = File(directory, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        context.reply(if (file.isFile && file.delete()) "Removed imported manifest: $id" else "No imported manifest found for: $id")
    }

    private fun list(context: AutomationContext) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
        if (files.isEmpty()) return context.reply("No imported plugin manifests yet. Use .install while replying to a .tdplugin file.")
        val lines = files.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                "${json.optString("name", file.nameWithoutExtension)} — ${json.optString("id")}"
            }.getOrNull()
        }
        context.reply("Imported plugin manifests (metadata only):\n" + lines.joinToString("\n"))
    }
}
