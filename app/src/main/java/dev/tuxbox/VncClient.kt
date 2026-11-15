package dev.tuxbox

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal RFB 3.8 client for a VNC server on localhost.
 * Security "None", 32bpp true colour, Raw + CopyRect + DesktopSize encodings.
 */
class VncClient(private val listener: Listener) {
    interface Listener {
        fun onConnected(name: String)
        fun onFrame()
        fun onDisconnected(error: String?)
    }

    val lock = Any()
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    private var pixels = IntArray(0)
    /** Guarded by [lock]. */
    var bitmap: Bitmap? = null; private set

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    @Volatile private var closed = false
    @Volatile private var ready = false
    private val sender = Executors.newSingleThreadExecutor()

    /** Blocking: connects, then runs the receive loop until disconnected. */
    fun run(host: String, port: Int) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 3000)
            socket = s
            input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            handshake()
            ready = true
            loop()
        } catch (e: IOException) {
            if (!closed) listener.onDisconnected(e.message)
            return
        }
        if (!closed) listener.onDisconnected(null)
    }

    fun close() {
        closed = true
        sender.shutdownNow()
        try { socket?.close() } catch (_: IOException) {}
    }

    private fun handshake() {
        val ver = ByteArray(12).also { input.readFully(it) }
        if (!String(ver).startsWith("RFB ")) throw IOException("Not a VNC server")
        output.write("RFB 003.008\n".toByteArray()); output.flush()

        val n = input.readUnsignedByte()
        if (n == 0) throw IOException(readString())
        val types = ByteArray(n).also { input.readFully(it) }
        if (1.toByte() !in types) throw IOException("Server requires a password (security types ${types.toList()})")
        output.writeByte(1); output.flush()
        if (input.readInt() != 0) throw IOException("Security handshake failed: ${readString()}")

        output.writeByte(1) // shared session
        output.flush()

        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format, we override it
        val name = readString()
        resize(w, h)

        // SetPixelFormat: 32bpp, depth 24, little endian, true colour, RGB888
        output.writeByte(0); output.write(ByteArray(3))
        output.write(byteArrayOf(32, 24, 0, 1))
        output.writeShort(255); output.writeShort(255); output.writeShort(255)
        output.write(byteArrayOf(16, 8, 0)); output.write(ByteArray(3))
        // SetEncodings: CopyRect, Raw, DesktopSize
        val encodings = intArrayOf(1, 0, -223)
        output.writeByte(2); output.writeByte(0); output.writeShort(encodings.size)
        encodings.forEach { output.writeInt(it) }
        output.flush()
        requestUpdate(false)
        listener.onConnected(name)
    }

    private fun readString(): String {
        val len = input.readInt()
        return String(ByteArray(len).also { input.readFully(it) })
    }

    private fun resize(w: Int, h: Int) = synchronized(lock) {
        width = w; height = h
        pixels = IntArray(w * h)
        bitmap = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
    }

    private fun requestUpdate(incremental: Boolean) = synchronized(output) {
        output.writeByte(3)
        output.writeByte(if (incremental) 1 else 0)
        output.writeShort(0); output.writeShort(0)
        output.writeShort(width); output.writeShort(height)
        output.flush()
    }

    private fun loop() {
        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                0 -> { readFramebufferUpdate(); listener.onFrame(); requestUpdate(true) }
                1 -> { input.skipBytes(3); val count = input.readUnsignedShort(); input.skipBytes(count * 6) }
                2 -> Unit // bell
                3 -> { input.skipBytes(3); input.skipBytes(input.readInt()) } // server clipboard
                else -> throw IOException("Unknown server message $type")
            }
        }
    }

    private fun readFramebufferUpdate() {
        input.skipBytes(1)
        val rects = input.readUnsignedShort()
        repeat(rects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val enc = input.readInt()) {
                0 -> readRaw(x, y, w, h)
                1 -> copyRect(input.readUnsignedShort(), input.readUnsignedShort(), x, y, w, h)
                -223 -> resize(w, h)
                else -> throw IOException("Unsupported encoding $enc")
            }
        }
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val row = ByteArray(w * 4)
        val tmp = IntArray(w)
        for (r in 0 until h) {
            input.readFully(row)
            var j = 0
            for (i in 0 until w) {
                tmp[i] = (0xFF shl 24) or
                    ((row[j + 2].toInt() and 0xFF) shl 16) or
                    ((row[j + 1].toInt() and 0xFF) shl 8) or
                    (row[j].toInt() and 0xFF)
                j += 4
            }
            synchronized(lock) {
                if (y + r < height && x + w <= width) System.arraycopy(tmp, 0, pixels, (y + r) * width + x, w)
            }
        }
        flush(x, y, w, h)
    }

    private fun copyRect(sx: Int, sy: Int, x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            if (sx + w > width || sy + h > height || x + w > width || y + h > height) return
            val tmp = IntArray(w * h)
            for (r in 0 until h) System.arraycopy(pixels, (sy + r) * width + sx, tmp, r * w, w)
            for (r in 0 until h) System.arraycopy(tmp, r * w, pixels, (y + r) * width + x, w)
        }
        flush(x, y, w, h)
    }

    private fun flush(x: Int, y: Int, w: Int, h: Int) = synchronized(lock) {
        val bmp = bitmap ?: return
        if (w == 0 || h == 0 || x + w > bmp.width || y + h > bmp.height) return
        bmp.setPixels(pixels, y * width + x, width, x, y, w, h)
    }

    // ------------------------------------------------------------- input

    private fun send(block: DataOutputStream.() -> Unit) {
        if (closed || !ready) return
        try {
            sender.execute {
                try { synchronized(output) { output.block(); output.flush() } } catch (_: IOException) {}
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    fun pointer(x: Int, y: Int, buttons: Int) = send {
        writeByte(5); writeByte(buttons)
        writeShort(x.coerceIn(0, maxOf(width - 1, 0))); writeShort(y.coerceIn(0, maxOf(height - 1, 0)))
    }

    fun key(keysym: Int, down: Boolean) = send {
        writeByte(4); writeByte(if (down) 1 else 0); writeShort(0); writeInt(keysym)
    }
}
