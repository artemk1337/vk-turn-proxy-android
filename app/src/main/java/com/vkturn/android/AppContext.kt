package com.vkturn.android

import android.content.Context
import android.content.res.Configuration
import java.io.File
import java.util.Locale

internal fun localizedAppContext(base: Context): Context {
    val preferences = AppPreferences.load(File(base.filesDir, "preferences.properties"))
    val config = Configuration(base.resources.configuration)
    config.setLocale(Locale.forLanguageTag(preferences.language))
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        if (preferences.theme == "dark") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    return base.createConfigurationContext(config)
}
