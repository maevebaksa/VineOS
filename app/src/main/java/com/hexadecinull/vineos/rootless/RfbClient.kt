package com.hexadecinull.vineos.rootless

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Tiny RFB 3.8 client used by the rootless QEMU backend.
 *
 * QEMU exposes its virtio-gpu framebuffer over a loopback-only VNC socket. Keeping
 * the RFB client in-process lets VineOS continue to render into the existing
 * SurfaceView and means no external VNC application is required.
 *
 * We deliberately request only the mandatory RAW encoding. It costs more CPU than
 * Tight/ZRLE but is deterministic and keeps the first rootless implementation small.
 */
class RfbClient(
    private val host: String,
    private val port: Int,
) {
    private val tag = "VineRfb"
    private val running = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var framebufferWidth = 0
    @Volatile private var framebufferHeight = 0
    @Volatile private var lastSurfaceWidth = 0
    @Volatile private var lastSurfaceHeight = 0
    @Volatile private var pointerDown = false
    @Volatile private var bitmap: Bitmap? = null
    private var worker: Thread? = null

    fun attachSurface(newSurface: Surface) {
        surface = newSurface
        drawFrame()
    }

    fun detachSurface() {
        surface = null
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        worker = Thread({ runClient() }, "vine-rfb-$port").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        output = null
        worker = null
        bitmap?.recycle()
        bitmap = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    fun sendPointer(action: Int, hostX: Float, hostY: Float) {
        val fw = framebufferWidth
        val fh = framebufferHeight
        if (fw <= 0 || fh <= 0) return

        val mapped = mapSurfaceToGuest(hostX, hostY, fw, fh)
        when (action) {
            MotionEvent.ACTION_DOWN -> pointerDown = true
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> pointerDown = false
        }
        sendPointerEvent(if (pointerDown) 1 else 0, mapped.first, mapped.second)
    }

    fun sendAndroidKey(keyCode: Int, down: Boolean) {
        val keysym = when (keyCode) {
            // XF86 Back/HomePage map more naturally to Android's navigation keys
            // than desktop Escape/Home when QEMU exposes a USB keyboard.
            KeyEvent.KEYCODE_BACK -> 0x1008ff26
            KeyEvent.KEYCODE_HOME -> 0x1008ff18
            KeyEvent.KEYCODE_APP_SWITCH -> 0xffc8 // F11, best-effort until direct guest key injection is added
            KeyEvent.KEYCODE_ENTER -> 0xff0d
            KeyEvent.KEYCODE_DEL -> 0xff08
            KeyEvent.KEYCODE_DPAD_UP -> 0xff52
            KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
            KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
            else -> return
        }
        synchronized(writeLock) {
            val out = output ?: return
            runCatching {
                out.writeByte(4)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0)
                out.writeInt(keysym)
                out.flush()
            }
        }
    }

    private fun runClient() {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 10_000)
            socket = s
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            output = out

            handshake(input, out)
            requestPixelFormat(out)
            requestRawEncoding(out)
            requestFramebufferUpdate(out, incremental = false)

            while (running.get()) {
                when (input.readUnsignedByte()) {
                    0 -> readFramebufferUpdate(input, out)
                    1 -> readColourMap(input)
                    2 -> Unit // Bell
                    3 -> readServerCutText(input)
                    else -> throw IllegalStateException("Unsupported RFB server message")
                }
            }
        } catch (t: Throwable) {
            if (running.get()) Log.e(tag, "RFB client stopped: ${t.message}", t)
        } finally {
            runCatching { socket?.close() }
            socket = null
            output = null
            running.set(false)
        }
    }

    private fun handshake(input: DataInputStream, out: DataOutputStream) {
        val versionBytes = ByteArray(12)
        input.readFully(versionBytes)
        val serverVersion = String(versionBytes, StandardCharsets.US_ASCII)
        if (!serverVersion.startsWith("RFB 003.")) {
            throw IllegalStateException("Invalid RFB banner: $serverVersion")
        }

        out.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()

        val securityCount = input.readUnsignedByte()
        if (securityCount == 0) {
            val length = input.readInt()
            val reason = ByteArray(length.coerceIn(0, 64 * 1024))
            input.readFully(reason)
            throw IllegalStateException("RFB security failure: ${String(reason, StandardCharsets.UTF_8)}")
        }
        val types = ByteArray(securityCount)
        input.readFully(types)
        if (types.none { it.toInt() and 0xff == 1 }) {
            throw IllegalStateException("QEMU VNC did not offer no-auth RFB security")
        }
        out.writeByte(1) // None
        out.flush()
        val result = input.readInt()
        if (result != 0) throw IllegalStateException("RFB security result=$result")

        out.writeByte(1) // shared desktop
        out.flush()

        framebufferWidth = input.readUnsignedShort()
        framebufferHeight = input.readUnsignedShort()
        val serverPixelFormat = ByteArray(16)
        input.readFully(serverPixelFormat)
        val nameLength = input.readInt()
        if (nameLength < 0 || nameLength > 1024 * 1024) throw IllegalStateException("Invalid RFB name length")
        val name = ByteArray(nameLength)
        input.readFully(name)
        Log.i(tag, "Connected to ${String(name, StandardCharsets.UTF_8)} ${framebufferWidth}x${framebufferHeight}")
        bitmap = Bitmap.createBitmap(framebufferWidth, framebufferHeight, Bitmap.Config.ARGB_8888)
    }

    private fun requestPixelFormat(out: DataOutputStream) = synchronized(writeLock) {
        // 32bpp little-endian true-colour: memory bytes are B, G, R, X.
        out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0))
        out.writeByte(32)
        out.writeByte(24)
        out.writeByte(0)
        out.writeByte(1)
        out.writeShort(255)
        out.writeShort(255)
        out.writeShort(255)
        out.writeByte(16)
        out.writeByte(8)
        out.writeByte(0)
        out.write(byteArrayOf(0, 0, 0))
        out.flush()
    }

    private fun requestRawEncoding(out: DataOutputStream) = synchronized(writeLock) {
        out.writeByte(2)
        out.writeByte(0)
        out.writeShort(1)
        out.writeInt(0) // RAW
        out.flush()
    }

    private fun requestFramebufferUpdate(out: DataOutputStream, incremental: Boolean) = synchronized(writeLock) {
        out.writeByte(3)
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(framebufferWidth)
        out.writeShort(framebufferHeight)
        out.flush()
    }

    private fun readFramebufferUpdate(input: DataInputStream, out: DataOutputStream) {
        input.readUnsignedByte() // padding
        val count = input.readUnsignedShort()
        val fb = bitmap ?: return

        repeat(count) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            if (encoding != 0) throw IllegalStateException("Unexpected RFB encoding $encoding")
            if (x + w > framebufferWidth || y + h > framebufferHeight) {
                throw IllegalStateException("RFB rectangle outside framebuffer")
            }

            val rowBytes = ByteArray(w * 4)
            val pixels = IntArray(w * h)
            var offset = 0
            repeat(h) {
                input.readFully(rowBytes)
                var p = 0
                repeat(w) {
                    val b = rowBytes[p].toInt() and 0xff
                    val g = rowBytes[p + 1].toInt() and 0xff
                    val r = rowBytes[p + 2].toInt() and 0xff
                    pixels[offset++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                    p += 4
                }
            }
            fb.setPixels(pixels, 0, w, x, y, w, h)
        }
        drawFrame()
        requestFramebufferUpdate(out, incremental = true)
    }

    private fun drawFrame() {
        val target = surface ?: return
        val fb = bitmap ?: return
        if (!target.isValid) return
        var canvas: Canvas? = null
        try {
            canvas = target.lockCanvas(null)
            lastSurfaceWidth = canvas.width
            lastSurfaceHeight = canvas.height
            canvas.drawColor(Color.BLACK)
            val dest = fitCenterRect(canvas.width, canvas.height, fb.width, fb.height)
            canvas.drawBitmap(fb, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        } catch (t: Throwable) {
            Log.w(tag, "Surface draw failed: ${t.message}")
        } finally {
            if (canvas != null) runCatching { target.unlockCanvasAndPost(canvas) }
        }
    }

    private fun fitCenterRect(sw: Int, sh: Int, fw: Int, fh: Int): Rect {
        if (sw <= 0 || sh <= 0 || fw <= 0 || fh <= 0) return Rect(0, 0, sw, sh)
        val scale = minOf(sw.toFloat() / fw, sh.toFloat() / fh)
        val width = (fw * scale).roundToInt()
        val height = (fh * scale).roundToInt()
        val left = (sw - width) / 2
        val top = (sh - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun mapSurfaceToGuest(x: Float, y: Float, fw: Int, fh: Int): Pair<Int, Int> {
        val sw = lastSurfaceWidth.coerceAtLeast(1)
        val sh = lastSurfaceHeight.coerceAtLeast(1)
        val dest = fitCenterRect(sw, sh, fw, fh)
        val gx = (((x - dest.left) / dest.width().coerceAtLeast(1)) * fw).roundToInt().coerceIn(0, fw - 1)
        val gy = (((y - dest.top) / dest.height().coerceAtLeast(1)) * fh).roundToInt().coerceIn(0, fh - 1)
        return gx to gy
    }

    private fun sendPointerEvent(mask: Int, x: Int, y: Int) {
        synchronized(writeLock) {
            val out = output ?: return@synchronized
            runCatching {
                out.writeByte(5)
                out.writeByte(mask)
                out.writeShort(x)
                out.writeShort(y)
                out.flush()
            }
        }
    }

    private fun readColourMap(input: DataInputStream) {
        input.readUnsignedByte()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val bytes = ByteArray(count * 6)
        input.readFully(bytes)
    }

    private fun readServerCutText(input: DataInputStream) {
        input.skipBytes(3)
        val length = input.readInt()
        if (length < 0 || length > 16 * 1024 * 1024) throw IllegalStateException("Invalid clipboard length")
        val bytes = ByteArray(length)
        input.readFully(bytes)
    }
}
