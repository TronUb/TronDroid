package com.vikram.trondroid.plugins

import android.content.Context
import android.net.Uri
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import java.io.File

object ExternalPluginLoader {
    private val loadedClassLoaders = mutableListOf<ClassLoader>()

    @Synchronized
    fun import(context: Context, uri: Uri): List<Any> {
        val directory = File(context.filesDir, "plugins").apply { mkdirs() }
        val file = File(directory, "external_${System.currentTimeMillis()}.dex")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to read selected plugin file")

        require(file.length() > 0L) { "Selected plugin file is empty" }
        val optimized = File(context.codeCacheDir, "external-plugins").apply { mkdirs() }
        val loader = DexClassLoader(file.absolutePath, optimized.absolutePath, null, context.classLoader)
        val dex = DexFile(file)
        val instances = mutableListOf<Any>()
        val entries = dex.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement()
            val type = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            if (type.isInterface || java.lang.reflect.Modifier.isAbstract(type.modifiers)) continue
            if (type.name.startsWith("java.") || type.name.startsWith("android.") || type.name.startsWith("kotlin.")) continue
            val supported = type.interfaces.any {
                it.name == "com.vikram.trondroid.automation.AutomationPlugin" ||
                    it.name == "com.vikram.trondroid.telegram.BotCommandPlugin"
            }
            if (!supported) continue
            val instance = runCatching { type.getDeclaredConstructor().newInstance() }.getOrNull() ?: continue
            instances += instance
        }
        require(instances.isNotEmpty()) {
            "No compatible plugin class found. Implement AutomationPlugin or BotCommandPlugin and provide a public no-argument constructor."
        }
        loadedClassLoaders += loader
        return instances
    }
}
