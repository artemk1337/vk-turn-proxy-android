package com.vkturn.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savesReadsAndDeletesProfile() {
        val store = ProfileStore(temporary.newFolder())
        val id = store.save("{\"private\":\"secret\"}")
        assertEquals(listOf(id), store.ids())
        assertEquals("{\"private\":\"secret\"}", store.read(id))
        store.delete(id)
        assertTrue(store.ids().isEmpty())
    }

    @Test fun rejectsTraversalAndNoncanonicalIdentifiers() {
        assertFalse(ProfileStore.validId("../profile"))
        assertFalse(ProfileStore.validId("1-1-1-1-1"))
        assertTrue(ProfileStore.validId("145d47b1-dfb5-4606-97fa-d9e5e7fd3c69"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedProfiles() {
        ProfileStore(temporary.newFolder()).save("a".repeat(MAX_PROFILE_BYTES + 1))
    }
}
