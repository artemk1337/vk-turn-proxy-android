package com.vkturn.android

internal enum class ProfileInputMode { LINK, WIREGUARD }

internal class ProfileInput(
    var mode: ProfileInputMode = ProfileInputMode.LINK,
    var link: String = "",
    var wireGuard: String = "",
) {
    fun current(): String = if (mode == ProfileInputMode.LINK) link else wireGuard

    fun select(next: ProfileInputMode, current: String) {
        if (mode == ProfileInputMode.LINK) link = current else wireGuard = current
        mode = next
    }

    fun loadFile(text: String) {
        mode = detectMode(text)
        if (mode == ProfileInputMode.LINK) link = text else wireGuard = text
    }

    companion object {
        fun initial(text: String): ProfileInput = if (detectMode(text) == ProfileInputMode.WIREGUARD) {
            ProfileInput(ProfileInputMode.WIREGUARD, wireGuard = text)
        } else ProfileInput(link = text)

        fun detectMode(text: String): ProfileInputMode {
            val first = text.trimStart().removePrefix("\uFEFF").lineSequence().map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }.orEmpty()
            return if (first.startsWith("[")) ProfileInputMode.WIREGUARD else ProfileInputMode.LINK
        }
    }
}
