package com.vikram.trondroid.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.vikram.trondroid.security.SecretVault

class BotStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("trondroid.store", Context.MODE_PRIVATE)
    private val vault = SecretVault()

    fun all(): List<BotProfile> {
        val json = runCatching { JSONArray(prefs.getString("bots", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                val id = o.optString("id")
                add(BotProfile(
                    id = id,
                    name = o.optString("name"),
                    phoneNumber = o.optString("phone"),
                    apiId = o.optInt("apiId"),
                    enabled = prefs.getBoolean("enabled.$id", true),
                    botEnabled = prefs.getBoolean("botEnabled.$id", false),
                    botName = prefs.getString("botName.$id", null),
                    botUsername = prefs.getString("botUsername.$id", null),
                    botTelegramId = prefs.getString("botTelegramId.$id", null)?.toLongOrNull(),
                    botPhotoPath = prefs.getString("botPhotoPath.$id", null)
                ))
            }
        }
    }

    fun add(name: String, phone: String, apiId: Int, apiHash: String): BotProfile {
        val p = BotProfile(java.util.UUID.randomUUID().toString(), name, phone, apiId, true)
        val json = JSONArray()
        (all() + p).forEach { b -> json.put(JSONObject().put("id", b.id).put("name", b.name)
            .put("phone", b.phoneNumber).put("apiId", b.apiId)) }
        prefs.edit().putString("bots", json.toString()).putString("apiHash." + p.id, vault.encrypt(apiHash))
            .putBoolean("enabled." + p.id, true).apply()
        return p
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(48)
        if (clean.isBlank()) return
        val json = JSONArray()
        all().forEach { bot ->
            json.put(JSONObject().put("id", bot.id).put("name", if (bot.id == id) clean else bot.name)
                .put("phone", bot.phoneNumber).put("apiId", bot.apiId))
        }
        prefs.edit().putString("bots", json.toString()).apply()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled.$id", enabled).apply()
    }

    fun apiHash(id: String): String? = prefs.getString("apiHash." + id, null)
        ?.let { runCatching { vault.decrypt(it) }.getOrNull() }

    fun updateApiHash(id: String, value: String) {
        prefs.edit().putString("apiHash." + id, vault.encrypt(value)).apply()
    }

    fun customCredentials(id: String): Map<String, String> {
        val encrypted = prefs.getString("customCredentials.$id", null) ?: return emptyMap()
        val raw = runCatching { vault.decrypt(encrypted) }.getOrNull() ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) put(key, value)
            }
        }
    }

    fun updateCustomCredentials(id: String, values: Map<String, String>) {
        val json = JSONObject()
        values.filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .forEach { (key, value) -> json.put(key.trim(), value) }
        val editor = prefs.edit()
        if (values.isEmpty()) editor.remove("customCredentials.$id")
        else editor.putString("customCredentials.$id", vault.encrypt(json.toString()))
        editor.apply()
    }

    fun setBotEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("botEnabled.$id", enabled).apply()
    }

    fun setBotIdentity(id: String, name: String?, username: String?, telegramId: Long?, photoPath: String? = null) {
        val editor = prefs.edit()
        if (name.isNullOrBlank()) editor.remove("botName.$id") else editor.putString("botName.$id", name)
        if (username.isNullOrBlank()) editor.remove("botUsername.$id") else editor.putString("botUsername.$id", username)
        if (telegramId == null || telegramId <= 0) editor.remove("botTelegramId.$id") else editor.putString("botTelegramId.$id", telegramId.toString())
        if (photoPath.isNullOrBlank()) editor.remove("botPhotoPath.$id") else editor.putString("botPhotoPath.$id", photoPath)
        editor.apply()
    }

    fun setBotName(id: String, name: String?) {
        val editor = prefs.edit()
        if (name.isNullOrBlank()) editor.remove("botName.$id")
        else editor.putString("botName.$id", name)
        editor.apply()
    }

    fun remove(id: String) {
        val json = JSONArray()
        all().filterNot { it.id == id }.forEach { b -> json.put(JSONObject().put("id", b.id)
            .put("name", b.name).put("phone", b.phoneNumber).put("apiId", b.apiId)) }
        prefs.edit().putString("bots", json.toString())
            .remove("apiHash.$id").remove("enabled.$id")
            .remove("customCredentials.$id").remove("botEnabled.$id").remove("botName.$id")
            .remove("botUsername.$id").remove("botTelegramId.$id").remove("botPhotoPath.$id").apply()
        context.filesDir.resolve("tdlib/$id").deleteRecursively()
    }
}
