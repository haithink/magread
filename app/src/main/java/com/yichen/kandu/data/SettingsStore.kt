package com.yichen.kandu.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** settings.json 读写 */
object SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(context: Context) = File(context.filesDir, "settings.json")

    fun load(context: Context): AppSettings {
        val f = file(context)
        if (!f.exists()) return AppSettings()
        return runCatching {
            json.decodeFromString(AppSettings.serializer(), f.readText())
        }.getOrDefault(AppSettings())
    }

    fun save(context: Context, settings: AppSettings) {
        file(context).writeText(json.encodeToString(AppSettings.serializer(), settings))
    }
}
