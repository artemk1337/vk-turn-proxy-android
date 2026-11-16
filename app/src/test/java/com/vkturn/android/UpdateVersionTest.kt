package com.vkturn.android

import org.junit.Assert.*
import org.junit.Test

class UpdateVersionTest {
    @Test fun comparesNumericStableVersions() {
        assertTrue(UpdateVersion.newer("v0.2.2", "0.2.1"))
        assertTrue(UpdateVersion.newer("v0.10.0", "0.9.9"))
        assertTrue(UpdateVersion.newer("v1.0.0", "0.99.99"))
        assertFalse(UpdateVersion.newer("v0.2.1", "0.2.1"))
        assertFalse(UpdateVersion.newer("v0.2.0", "0.2.1"))
        assertFalse(UpdateVersion.newer("v0.1.99", "0.2.1"))
    }

    @Test fun rejectsPrereleasesAndMalformedVersions() {
        for (value in listOf("v0.3.0-beta.1", "v0.3", "v01.2.3", "v-1.2.3", "v999999999999.0.0", "latest", "0.2.2/path")) {
            assertNull(value, UpdateVersion.parse(value))
            assertFalse(value, UpdateVersion.newer(value, "0.2.1"))
        }
        assertFalse(UpdateVersion.newer("0.2.2", "invalid"))
    }
}
