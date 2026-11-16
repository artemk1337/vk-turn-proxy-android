package com.vkturn.android

import java.io.File
import java.io.IOException
import java.util.UUID

internal const val MAX_PROFILE_BYTES = 4 * 1024 * 1024

internal class ProfileStore(private val directory: File) {
    init { check(directory.mkdirs() || directory.isDirectory) }

    fun ids(): List<String> = directory.listFiles().orEmpty()
        .filter { it.isFile && validId(it.nameWithoutExtension) && it.extension == "json" }
        .sortedBy { it.lastModified() }
        .map { it.nameWithoutExtension }

    fun save(json: String, existingId: String? = null): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PROFILE_BYTES) { "Profile exceeds 4 MiB" }
        val id = existingId ?: UUID.randomUUID().toString()
        require(validId(id)) { "Invalid profile ID" }
        val temporary = File.createTempFile("$id-", ".tmp", directory)
        try {
            java.io.FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            check(temporary.renameTo(file(id))) { "Could not save profile" }
            return id
        } finally {
            temporary.delete()
        }
    }

    fun read(id: String): String {
        val file = file(id)
        if (file.length() > MAX_PROFILE_BYTES) throw IOException("Profile exceeds 4 MiB")
        return file.inputStream().use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = it.read(buffer)
                if (size < 0) break
                if (output.size() + size > MAX_PROFILE_BYTES) throw IOException("Profile exceeds 4 MiB")
                output.write(buffer, 0, size)
            }
            output.toString("UTF-8")
        }
    }

    fun delete(id: String) { check(file(id).delete()) { "Could not delete profile" } }

    private fun file(id: String): File {
        require(validId(id)) { "Invalid profile ID" }
        return File(directory, "$id.json")
    }

    companion object {
        fun validId(id: String): Boolean = runCatching {
            UUID.fromString(id).toString() == id
        }.getOrDefault(false)
    }
}
