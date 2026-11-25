package com.befeast.sleepmusic.online

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Refresh runs on IO; the bounded local snapshot can be loaded at launcher entry. */
class CatalogRepository(private val file: File, private val fetch: () -> String = ::fetchCatalog) {
    fun load(): Catalog? = try { Catalog.parse(file.readText()) } catch (_: Exception) { null }
    fun refresh(): Catalog? {
        val previous = load()
        return try {
            val text = fetch()
            val candidate = Catalog.parse(text)
            require(candidate.version >= (previous?.version ?: 0))
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            try {
                part.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { part.delete() }
            candidate
        } catch (_: Exception) { previous }
    }
    companion object {
        const val URL = "https://html.me.uk/t/dremla/catalog/current.json"
        private fun fetchCatalog(): String {
            val connection = URL(URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false; connection.useCaches = false
                require(connection.responseCode == 200)
                return connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    require(bytes.size <= MAX_BYTES)
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                }
            } finally { connection.disconnect() }
        }
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
