package dev.bbkb.ime.core.distribution

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A real HTTP/1.1 server on a loopback socket, so cross-host redirects, ETag round trips and
 * truncated bodies are exercised for real. Raw sockets because `com.sun.net.httpserver` (module
 * `jdk.httpserver`) is not on the Android unit test compile classpath. Every response sends
 * `Connection: close`; requests and their headers are recorded for assertions.
 */
class LocalHttpServer : AutoCloseable {
    /** Bound to the wildcard address so both `127.0.0.1` and `localhost` work ([crossHostUrl]). */
    private val socket = ServerSocket(0)
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val counter = AtomicInteger(0)
    private val paths = Collections.synchronizedList(ArrayList<String>())
    private val ifNoneMatch = Collections.synchronizedList(ArrayList<String?>())
    private val userAgents = Collections.synchronizedList(ArrayList<String?>())

    @Volatile
    private var handler: (Exchange) -> Unit = { it.replyText(404, "no handler installed") }

    @Volatile
    private var running = true

    init {
        pool.execute {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (closed: Exception) {
                    break
                }
                pool.execute { serve(client) }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                val input = BufferedInputStream(it.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }

                val parts = requestLine.split(' ')
                val method = parts.getOrElse(0) { "GET" }
                val target = parts.getOrElse(1) { "/" }
                val path = target.substringBefore('?')

                counter.incrementAndGet()
                paths.add(path)
                ifNoneMatch.add(headers["if-none-match"])
                userAgents.add(headers["user-agent"])

                handler(Exchange(method, path, headers, it.getOutputStream()))
            }
        } catch (ignored: Throwable) {
            // A client hanging up mid-body (the cancellation test) makes the write throw.
        }
    }

    /** One CRLF-terminated line as ISO-8859-1, or `null` at end of stream. */
    private fun readLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(b.toChar())
        }
    }

    val port: Int get() = socket.localPort

    val requestCount: Int get() = counter.get()

    /** The request paths seen, oldest first. */
    fun requestedPaths(): List<String> = ArrayList(paths)

    /** The `If-None-Match` header of each request, oldest first; `null` where absent. */
    fun ifNoneMatchHeaders(): List<String?> = ArrayList(ifNoneMatch)

    /** The `User-Agent` of each request, oldest first. */
    fun userAgentHeaders(): List<String?> = ArrayList(userAgents)

    fun resetCounters() {
        counter.set(0)
        paths.clear()
        ifNoneMatch.clear()
        userAgents.clear()
    }

    fun respondWith(handler: (Exchange) -> Unit) {
        this.handler = handler
    }

    /** `http://127.0.0.1:<port><path>`. */
    fun url(path: String): String = "http://127.0.0.1:$port$path"

    /**
     * The same server under a different host name. `HttpURLConnection` will not follow a
     * host-changing redirect, which is what a GitHub release asset does.
     */
    fun crossHostUrl(path: String): String = "http://localhost:$port$path"

    override fun close() {
        running = false
        runCatching { socket.close() }
        pool.shutdownNow()
    }

    /** One request, and the ways a test can answer it. */
    class Exchange(
        val method: String,
        val path: String,
        private val headers: Map<String, String>,
        private val out: OutputStream,
    ) {
        /** A request header, case-insensitively; `null` when absent. */
        fun header(name: String): String? = headers[name.lowercase()]

        fun reply(status: Int, body: ByteArray, extra: Map<String, String> = emptyMap()) {
            writeHead(status, extra + mapOf("Content-Length" to body.size.toString()))
            out.write(body)
            out.flush()
        }

        fun replyText(status: Int, body: String, extra: Map<String, String> = emptyMap()) =
            reply(status, body.toByteArray(StandardCharsets.UTF_8), extra)

        /** A redirect with a `Location`. [status] is one of 301/302/303/307/308. */
        fun redirectTo(status: Int, location: String) {
            writeHead(status, mapOf("Location" to location, "Content-Length" to "0"))
            out.flush()
        }

        /** A bodyless `304 Not Modified`, echoing [etag]. */
        fun notModified(etag: String) {
            writeHead(304, mapOf("ETag" to etag))
            out.flush()
        }

        /** Announces [total] bytes and sends them slowly enough for a test to cancel mid-stream. */
        fun replySlowly(total: Int, chunkSize: Int = 1024, pauseMs: Long = 40L) {
            writeHead(200, mapOf("Content-Length" to total.toString()))
            val chunk = ByteArray(chunkSize) { 'x'.code.toByte() }
            var sent = 0
            while (sent < total) {
                val n = minOf(chunkSize, total - sent)
                out.write(chunk, 0, n)
                out.flush()
                sent += n
                Thread.sleep(pauseMs)
            }
        }

        private fun writeHead(status: Int, headers: Map<String, String>) {
            val head = StringBuilder("HTTP/1.1 $status ${reasonFor(status)}\r\n")
            for ((name, value) in headers) head.append("$name: $value\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        }

        private fun reasonFor(status: Int): String = when (status) {
            200 -> "OK"
            301 -> "Moved Permanently"
            302 -> "Found"
            303 -> "See Other"
            304 -> "Not Modified"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Status $status"
        }
    }

    companion object {
        /** A just-released port with nothing listening, for connection-refused tests. */
        fun closedPort(): Int = ServerSocket(0).use { it.localPort }
    }
}
