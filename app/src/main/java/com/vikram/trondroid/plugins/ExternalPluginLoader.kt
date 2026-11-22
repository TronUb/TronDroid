package com.vikram.trondroid.plugins

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Imports portable plugin manifests as metadata only.
 *
 * Arbitrary DEX/APK/JAR execution remains blocked: third-party code would inherit
 * the app's Telegram session, credentials and file permissions.
 */
object ExternalPluginLoader {
    @Synchronized
    fun importManifest(context: Context, uri: Uri): String {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Unable to read the selected plugin manifest.")
        require(raw.toByteArray(Charsets.UTF_8).size in 1..128 * 1024) {
            "Manifest must be between 1 byte and 128 KB."
        }
        val json = JSONObject(raw)
        require(json.optString("format") == "trondroid-plugin-manifest") {
            "Unsupported plugin manifest format."
        }
        require(json.optInt("schema_version") == 1) { "Unsupported manifest version." }
        val id = json.optString("id")
        require(id.matches(Regex("[A-Za-z0-9:_-]{3,80}"))) { "Invalid plugin ID." }
        val name = json.optString("name").trim().take(80)
        require(name.isNotBlank()) { "Plugin name is missing." }
        val commands = json.optJSONArray("commands") ?: JSONArray()
        require(commands.length() in 0..32) { "A manifest may contain up to 32 commands." }
        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid command entry.")
            require(command.optString("name").matches(Regex("[A-Za-z0-9_]{1,32}"))) {
                "Invalid command name in manifest."
            }
        }
        val directory = File(context.filesDir, "shared-plugin-manifests").apply { mkdirs() }
        val fileName = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"
        File(directory, fileName).writeText(json.toString(2))
        return name
    }

    @Synchronized
    fun loadFromUri(context: Context, uri: Uri): List<Any> {
        throw SecurityException(
            "Executable plugins are not supported. Import a validated .tdplugin manifest; imported files are metadata only."
        )
    }
}
