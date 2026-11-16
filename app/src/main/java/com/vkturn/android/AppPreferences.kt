package com.vkturn.android

import java.io.File
import java.util.Properties

data class AppPreferences(
    val language: String = if (java.util.Locale.getDefault().language == "ru") "ru" else "en",
    val theme: String = "light",
    val autostart: Boolean = false,
    val logLines: Int = 1000,
    val routingAutoUpdate: Boolean = true,
    val activeId: String = "",
    val routingUpdatedAt: Long = 0,
) {
    fun withAvailableProfiles(ids: List<String>): AppPreferences = copy(
        activeId = activeId.takeIf { it in ids } ?: ids.firstOrNull().orEmpty(),
    )

    fun save(file: File) {
        require(language in listOf("en", "ru"))
        require(theme in listOf("light", "dark"))
        require(logLines in 100..10000)
        require(activeId.isEmpty() || ProfileStore.validId(activeId))
        val values = Properties().apply {
            setProperty("language", language)
            setProperty("theme", theme)
            setProperty("autostart", autostart.toString())
            setProperty("logLines", logLines.toString())
            setProperty("routingAutoUpdate", routingAutoUpdate.toString())
            setProperty("activeId", activeId)
            setProperty("routingUpdatedAt", routingUpdatedAt.toString())
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            java.io.FileOutputStream(temporary).use {
                values.store(it, null)
                it.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not save preferences" }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        @Synchronized fun update(file: File, change: (AppPreferences) -> AppPreferences): AppPreferences {
            return change(load(file)).also { it.save(file) }
        }

        fun load(file: File): AppPreferences {
            val values = Properties()
            runCatching { file.inputStream().use(values::load) }
            return AppPreferences(
                language = values.getProperty("language").takeIf { it in listOf("en", "ru") } ?: AppPreferences().language,
                theme = values.getProperty("theme").takeIf { it in listOf("light", "dark") } ?: "light",
                autostart = values.getProperty("autostart") == "true",
                logLines = values.getProperty("logLines")?.toIntOrNull()?.takeIf { it in 100..10000 } ?: 1000,
                routingAutoUpdate = values.getProperty("routingAutoUpdate") != "false",
                activeId = values.getProperty("activeId").orEmpty().takeIf { it.isEmpty() || ProfileStore.validId(it) }.orEmpty(),
                routingUpdatedAt = values.getProperty("routingUpdatedAt")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            )
        }
    }
}
