package dev.avillagran.ot3android.core.transport

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Accepts one WebSocket (RFC 6455, text frames, no extensions) and hands the client's first
 * `Request` frame and the socket to [script]: enough to script T3's Effect RPC stream frames,
 * the same idea as lib-t3-rust's `StreamFixture`. Never a real T3 server.
 */
internal class FakeWebSocketServer(private val script: (request: JSONObject, socket: Peer) -> Unit) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val failure = AtomicReference<Throwable?>(null)
    private val thread = Thread {
        try {
            server.accept().use { socket ->
                val peer = Peer(socket)
                val request = checkNotNull(peer.read()) { "no Request frame" }
                check(request.getString("_tag") == "Request") { request.toString() }
                script(request, peer)
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        }
    }.apply { isDaemon = true; start() }

    val baseUrl = "http://127.0.0.1:${server.localPort}"

    /** Waits for the script and rethrows what it threw. */
    fun join(timeoutMs: Long = 5000) {
        thread.join(timeoutMs)
        check(!thread.isAlive) { "fake WebSocket script still running" }
        failure.get()?.let { throw it }
    }

    override fun close() = server.close()

    class Peer(socket: Socket) {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = socket.getOutputStream()

        init {
            socket.soTimeout = 10_000
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
            check(head.startsWith("GET /ws")) { head.toString() }
            val key = head.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
            )
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.flush()
        }

        /** The next client text frame as JSON; null once the client closes or the socket ends. */
        fun read(): JSONObject? {
            while (true) {
                val first = try { input.readUnsignedByte() } catch (end: IOException) { return null }
                val second = input.readUnsignedByte()
                var length = (second and 0x7f).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                val mask = ByteArray(4)
                if (second and 0x80 != 0) input.readFully(mask)
                val payload = ByteArray(length.toInt())
                input.readFully(payload)
                if (second and 0x80 != 0) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (first and 0x0f) {
                    0x1 -> return JSONObject(payload.toString(Charsets.UTF_8))
                    0x8 -> return null
                }
            }
        }

        /** One Effect RPC chunk of stream values. */
        fun chunk(vararg values: JSONObject) =
            send(JSONObject().put("_tag", "Chunk").put("requestId", "1").put("values", JSONArray(values.toList())))

        /** RFC6455 continuation splits inside a multibyte UTF-8 code point. */
        fun chunkSplitUtf8(value: JSONObject) {
            val bytes = JSONObject().put("_tag", "Chunk").put("requestId", "1")
                .put("values", JSONArray().put(value)).toString().toByteArray(Charsets.UTF_8)
            val split = bytes.indexOfFirst { it.toInt() and 0x80 != 0 } + 1
            check(split > 0)
            fun part(op: Int, data: ByteArray) {
                output.write(op)
                if (data.size < 126) output.write(data.size)
                else { output.write(126); output.write(data.size shr 8); output.write(data.size and 255) }
                output.write(data); output.flush()
            }
            part(0x01, bytes.copyOfRange(0, split))
            part(0x80, bytes.copyOfRange(split, bytes.size))
        }

        /** The Effect RPC `Exit` that ends the stream server-side. */
        fun exit() = send(
            JSONObject().put("_tag", "Exit").put("requestId", "1")
                .put("exit", JSONObject().put("_tag", "Failure").put("cause", JSONObject().put("_tag", "Die"))),
        )

        /** One server text frame. */
        fun send(frame: JSONObject) {
            val bytes = frame.toString().toByteArray(Charsets.UTF_8)
            val header = ByteArrayOutputStream()
            header.write(0x81)
            when {
                bytes.size < 126 -> header.write(bytes.size)
                bytes.size < 65536 -> { header.write(126); header.write(bytes.size shr 8); header.write(bytes.size and 0xff) }
                else -> { header.write(127); for (shift in 56 downTo 0 step 8) header.write((bytes.size.toLong() shr shift and 0xff).toInt()) }
            }
            output.write(header.toByteArray())
            output.write(bytes)
            output.flush()
        }

        /** Reads frames until [tag] arrives (true) or the socket ends (false); answers Pings. */
        fun awaitTag(tag: String): Boolean {
            while (true) {
                val frame = read() ?: return false
                if (frame.optString("_tag") == tag) return true
                if (frame.optString("_tag") == "Ping") {
                    val pong = "{\"_tag\":\"Pong\"}".toByteArray(Charsets.UTF_8)
                    output.write(byteArrayOf(0x81.toByte(), pong.size.toByte()))
                    output.write(pong)
                    output.flush()
                }
            }
        }
    }
}

internal fun item(vararg pairs: Pair<String, Any>) = JSONObject().apply { for ((k, v) in pairs) put(k, v) }
