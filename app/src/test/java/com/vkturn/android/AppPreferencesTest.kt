package com.vkturn.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = "145d47b1-dfb5-4606-97fa-d9e5e7fd3c69"

    @Test fun loadsDesktopDefaultsBeforePreferencesExist() {
        assertEquals(AppPreferences(), AppPreferences.load(File(temporary.root, "missing")))
    }

    @Test fun roundTripsAllSettingsAndSelectedProfile() {
        val file = File(temporary.root, "settings")
        val values = AppPreferences(language = "ru", theme = "dark", autostart = true,
            logLines = 4000, routingAutoUpdate = false, activeId = profile, routingUpdatedAt = 1234)
        values.save(file)
        assertEquals(values, AppPreferences.load(file))
        assertFalse(File(temporary.root, "settings.tmp").exists())
    }

    @Test fun migratesExistingProfilesAndRetainsValidSelection() {
        assertEquals(profile, AppPreferences().withAvailableProfiles(listOf(profile)).activeId)
        assertEquals(profile, AppPreferences(activeId = profile).withAvailableProfiles(listOf(profile)).activeId)
        assertEquals("", AppPreferences(activeId = profile).withAvailableProfiles(emptyList()).activeId)
    }

    @Test fun rejectsUnsafeOrInvalidPersistedSettings() {
        val file = File(temporary.root, "settings")
        file.writeText("language=xx\ntheme=other\nlogLines=99999\nactiveId=../private\nroutingUpdatedAt=-1\n")
        val values = AppPreferences.load(file)
        assertEquals(AppPreferences().language, values.language)
        assertEquals("light", values.theme)
        assertEquals(1000, values.logLines)
        assertEquals("", values.activeId)
        assertEquals(0L, values.routingUpdatedAt)
    }

    @Test fun appliesIndependentChangesToLatestSettings() {
        val file = File(temporary.root, "settings")
        AppPreferences.update(file) { it.copy(theme = "dark") }
        AppPreferences.update(file) { it.copy(activeId = profile) }
        assertEquals("dark", AppPreferences.load(file).theme)
        assertEquals(profile, AppPreferences.load(file).activeId)
    }
}
