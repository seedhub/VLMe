package org.videolan.tools

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Loopback HTTP server used to count requests and observe whether the client
 * closes the socket. Not a general HTTP stack: it reads one request and writes
 * one response per accepted connection.
 */
internal class LocalImageServer : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort
    private val stop = AtomicBoolean(false)

    val requests = AtomicInteger(0)
    val clientCloses = AtomicInteger(0)
    val handler = AtomicReference<(Session) -> Unit> { session ->
        session.respond(200, TINY_PNG)
        session.trackClientClose(3_000)
    }

    private val acceptThread = Thread({ acceptLoop() }, "image-test-accept").apply {
        isDaemon = true
        start()
    }

    fun url(path: String = "/image.png"): String = "http://127.0.0.1:$port$path"

    fun awaitRequests(count: Int, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (requests.get() >= count) return true
            Thread.sleep(20)
        }
        return requests.get() >= count
    }

    fun awaitClientCloses(count: Int, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (clientCloses.get() >= count) return true
            Thread.sleep(20)
        }
        return clientCloses.get() >= count
    }

    private fun acceptLoop() {
        while (!stop.get()) {
            val client = try {
                socket.accept()
            } catch (ignored: Exception) {
                if (stop.get()) break else continue
            }
            Thread({
                try {
                    handle(client)
                } catch (ignored: Exception) {
                } finally {
                    try {
                        client.close()
                    } catch (ignored: Exception) {
                    }
                }
            }, "image-test-client").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        val input = client.getInputStream()
        val output = client.getOutputStream()
        if (!readHeaders(input)) return
        requests.incrementAndGet()
        handler.get().invoke(Session(this, client, input, output))
    }

    override fun close() {
        stop.set(true)
        try {
            socket.close()
        } catch (ignored: Exception) {
        }
        acceptThread.join(1_000)
    }

    class Session(
        private val server: LocalImageServer,
        private val socket: Socket,
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        fun respond(code: Int, body: ByteArray, reason: String = "OK") {
            val header = "HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\n" +
                "Connection: close\r\nContent-Type: image/png\r\n\r\n"
            writeQuietly(header.toByteArray(Charsets.ISO_8859_1))
            writeQuietly(body)
            try {
                output.flush()
            } catch (ignored: Exception) {
            }
        }

        /**
         * Sends headers and a single body byte, then waits. The client blocks in
         * the middle of the download until [release] opens.
         */
        fun holdAfterFirstByte(body: ByteArray, started: CountDownLatch, release: CountDownLatch) {
            val header = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                "Connection: close\r\nContent-Type: image/png\r\n\r\n"
            writeQuietly(header.toByteArray(Charsets.ISO_8859_1))
            if (body.isNotEmpty()) writeQuietly(byteArrayOf(body[0]))
            try {
                output.flush()
            } catch (ignored: Exception) {
            }
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            if (body.size > 1) writeQuietly(body.copyOfRange(1, body.size))
            try {
                output.flush()
            } catch (ignored: Exception) {
            }
        }

        fun trackClientClose(timeoutMs: Int) {
            socket.soTimeout = timeoutMs
            val closed = try {
                input.read() < 0
            } catch (ignored: SocketTimeoutException) {
                false
            } catch (ignored: Exception) {
                true
            }
            if (closed) server.clientCloses.incrementAndGet()
        }

        private fun writeQuietly(bytes: ByteArray) {
            try {
                output.write(bytes)
            } catch (ignored: Exception) {
            }
        }
    }

    companion object {
        /** 1×1 PNG. */
        val TINY_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
    }
}

private fun readHeaders(input: InputStream): Boolean {
    val out = ByteArrayOutputStream()
    var matched = 0
    val marker = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    while (matched < marker.size && out.size() < 16 * 1024) {
        val value = input.read()
        if (value < 0) return false
        out.write(value)
        matched = if (value == marker[matched].toInt() && value < 256) {
            matched + 1
        } else if (value == marker[0].toInt()) {
            1
        } else {
            0
        }
    }
    return matched == marker.size
}
