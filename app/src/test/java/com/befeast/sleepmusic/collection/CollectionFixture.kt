package com.befeast.sleepmusic.collection

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** EOF-delimited bodies, deliberately no Content-Length and no Range handling. */
class CollectionFixture : AutoCloseable {
    data class Response(val body: ByteArray, val status: Int = 200, val location: String? = null)
    val routes = ConcurrentHashMap<String, Response>()
    val requests = CopyOnWriteArrayList<String>()
    val headers = CopyOnWriteArrayList<String>()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}/"
    private val worker = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            socket.use {
                val input = it.getInputStream().bufferedReader()
                val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                while (true) {
                    val header = input.readLine() ?: break
                    if (header.isEmpty()) break
                    headers += header
                }
                requests += path
                val response = routes[path] ?: Response(byteArrayOf(), 503)
                runCatching {
                    it.getOutputStream().apply {
                        write("HTTP/1.1 ${response.status} Fixture\r\nConnection: close\r\n".toByteArray())
                        response.location?.let { location -> write("Location: $location\r\n".toByteArray()) }
                        write("\r\n".toByteArray())
                        write(response.body)
                        flush()
                    }
                }
            }
        }
    }
    fun manifest(selection: List<String>, bodies: Map<String, ByteArray>, hashes: Map<String, String> = emptyMap()) {
        val tracks = JSONArray()
        bodies.forEach { (id, body) ->
            tracks.put(JSONObject().put("id", id).put("title", id).put("file", "audio/$id.opus")
                .put("bytes", body.size).put("sha256", hashes[id] ?: sha(body)))
            routes["/audio/$id.opus"] = Response(body)
        }
        routes["/manifest.json"] = Response(JSONObject().put("version", 1).put("tracks", tracks)
            .put("selection", JSONArray(selection)).toString().toByteArray())
    }
    override fun close() { server.close(); worker.join(2000) }
    companion object {
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
