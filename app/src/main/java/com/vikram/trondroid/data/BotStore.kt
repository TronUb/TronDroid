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
                add(BotProfile(o.optString("id"), o.optString("name"), o.optString("phone"), o.optInt("apiId")))
            }
        }
    }
    fun add(name: String, phone: String, apiId: Int, apiHash: String): BotProfile {
        val p = BotProfile(java.util.UUID.randomUUID().toString(), name, phone, apiId)
        val json = JSONArray()
        (all() + p).forEach { b -> json.put(JSONObject().put("id", b.id).put("name", b.name)
            .put("phone", b.phoneNumber).put("apiId", b.apiId)) }
        prefs.edit().putString("bots", json.toString()).putString("apiHash." + p.id, vault.encrypt(apiHash)).apply()
        return p
    }
    fun apiHash(id: String): String? = prefs.getString("apiHash." + id, null)
        ?.let { runCatching { vault.decrypt(it) }.getOrNull() }
    fun updateApiHash(id: String, value: String) {
        prefs.edit().putString("apiHash." + id, vault.encrypt(value)).apply()
    }
    fun remove(id: String) {
        val json = JSONArray()
        all().filterNot { it.id == id }.forEach { b -> json.put(JSONObject().put("id", b.id)
            .put("name", b.name).put("phone", b.phoneNumber).put("apiId", b.apiId)) }
        prefs.edit().putString("bots", json.toString()).remove("apiHash." + id).apply()
        context.filesDir.resolve("tdlib/$id").deleteRecursively()
    }
}
