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
 * A real HTTP/1.1 server on a real loopback socket, spoken directly over [ServerSocket].
 *
 * The network code in this package exists to cope with things a mock cannot express — a
 * cross-host `302` that `HttpURLConnection` would silently drop, an `ETag` round trip, a body
 * that stops arriving halfway through. A stubbed `HttpURLConnection` would only ever prove that
 * the stub behaves like the stub, so these tests talk to an actual socket and everything
 * [Downloader] and [ManifestSource] do to it is the same thing they do to GitHub.
 *
 * It is written on raw sockets rather than on the JDK's own
 * `com.sun.net.httpserver.HttpServer` because that class lives in the `jdk.httpserver` module,
 * which is **not** on an Android unit test's compile classpath: the compiler sees `android.jar`
 * plus `java.base` and nothing else, so importing it fails to compile even though it would be
 * there at runtime under Robolectric. Fifty lines of HTTP/1.1 is a smaller price than teaching
 * the build to add a JDK module to one source set.
 *
 * Every response closes its connection (`Connection: close`), which keeps the implementation
 * honest about content length and keeps keep-alive out of the picture. Requests are counted and
 * their headers recorded — that is how "did not go to the network at all" and "sent
 * If-None-Match" are asserted.
 */
class LocalHttpServer : AutoCloseable {

    /**
     * Bound to the wildcard address, not to `127.0.0.1`, so the server answers on *both*
     * `127.0.0.1` and `localhost`. That is what makes [crossHostUrl] a genuine change of host
     * from the client's point of view.
     */
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
            // A client that hangs up mid-body (the cancellation test) makes the write throw.
            // That is the scenario under test, not a failure of the server.
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

    /** How many requests have reached the server. */
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

    /** Install the response behaviour for subsequent requests. */
    fun respondWith(handler: (Exchange) -> Unit) {
        this.handler = handler
    }

    /** `http://127.0.0.1:<port><path>`. */
    fun url(path: String): String = "http://127.0.0.1:$port$path"

    /**
     * The same server under a *different host name*. `HttpURLConnection` refuses to follow a
     * redirect that changes the host, which is exactly what a GitHub release asset does, so a
     * redirect from [url] to this is the cross-host case in miniature.
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

        /** Send [body] with [status] and any [extra] headers. */
        fun reply(status: Int, body: ByteArray, extra: Map<String, String> = emptyMap()) {
            writeHead(status, extra + mapOf("Content-Length" to body.size.toString()))
            out.write(body)
            out.flush()
        }

        /** Send [body] as UTF-8 with [status]. */
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

        /**
         * Announce [total] bytes, then dribble them out in [chunkSize] pieces with [pauseMs]
         * between — long enough for a test to cancel the download mid-stream.
         */
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
        /**
         * A port with nothing listening on it, for the connection-refused tests. Bound and
         * released, so in principle something else could take it; in practice a just-freed
         * ephemeral port is not reused this quickly.
         */
        fun closedPort(): Int = ServerSocket(0).use { it.localPort }
    }
}
