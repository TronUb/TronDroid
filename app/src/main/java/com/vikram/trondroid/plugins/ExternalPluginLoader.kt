package com.vikram.trondroid.plugins

import android.content.Context
import android.net.Uri

/**
 * Executable plugin loading is intentionally disabled.
 *
 * A DEX file runs with the application's permissions and can access the authenticated
 * Telegram session, private files and credentials. TronDroid has no trusted signing
 * and permission-isolation model for third-party code, so accepting arbitrary DEX/APK/JAR
 * files would be unsafe. Declarative .tdplugin manifests remain supported by SharingAutomationPlugin.
 */
object ExternalPluginLoader {
    @Synchronized
    fun loadFromUri(context: Context, uri: Uri): List<Any> {
        throw SecurityException(
            "Executable external plugins are disabled for security. Use built-in plugins or import a .tdplugin metadata manifest; imported manifests never execute code."
        )
    }
}
