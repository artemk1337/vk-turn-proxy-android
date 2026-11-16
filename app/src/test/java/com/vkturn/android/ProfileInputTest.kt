package com.vkturn.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileInputTest {
    @Test fun modeSwitchKeepsBothUnfinishedSources() {
        val input = ProfileInput()
        input.select(ProfileInputMode.WIREGUARD, "vkturnproxy://import?data=incomplete")
        assertEquals("", input.current())
        input.select(ProfileInputMode.LINK, "[Interface]\nPrivateKey = unfinished")
        assertEquals("vkturnproxy://import?data=incomplete", input.current())
        input.select(ProfileInputMode.WIREGUARD, input.current())
        assertEquals("[Interface]\nPrivateKey = unfinished", input.current())
    }

    @Test fun fileDetectionAcceptsWireGuardCommentsAndBom() {
        val input = ProfileInput(link = "existing link")
        input.loadFile(" \n\uFEFF# exported configuration\r\n; comment\r\n[Interface]\r\n")
        assertEquals(ProfileInputMode.WIREGUARD, input.mode)
        assertEquals("existing link", input.link)
    }

    @Test fun initialWireGuardFileKeepsItsText() {
        val config = "[Interface]\nMTU = 1420"
        val input = ProfileInput.initial(config)
        assertEquals(ProfileInputMode.WIREGUARD, input.mode)
        assertEquals(config, input.current())
        assertEquals("", input.link)
    }

    @Test fun jsonAndConnectionLinksKeepLinkMode() {
        for (source in listOf(" {\"version\":1}", "vkturnproxy://import?data=abc", "https://example.com/profile.conf", "eyJ2ZXJzaW9uIjoxfQ==")) {
            assertEquals(ProfileInputMode.LINK, ProfileInput.detectMode(source))
        }
    }
}
