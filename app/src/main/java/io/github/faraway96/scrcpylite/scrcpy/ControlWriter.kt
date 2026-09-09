package io.github.faraway96.scrcpylite.scrcpy

import io.github.faraway96.scrcpylite.adb.AdbStream
import java.io.ByteArrayOutputStream

/**
 * scrcpy 控制消息写入 (v2.7 协议, 大端序)。
 * 消息类型常量与字节布局与服务端 ControlMessageReader 一致。
 */
class ControlWriter(private val stream: AdbStream) {
    private val lock = Object()

    companion object {
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_ROTATE_DEVICE = 11

        // Android MotionEvent action
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        // scrcpy 泛指手指的 pointerId
        const val POINTER_ID_FINGER = -1L

        fun i16FixedPoint(v: Float): Short =
            (v * 32768f).toInt().coerceIn(-0x8000, 0x7FFF).toShort()
    }

    private fun send(bytes: ByteArray) {
        synchronized(lock) { stream.write(bytes) }
    }

    private fun put32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte()
        buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte()
        buf[off + 3] = v.toByte()
    }

    private fun put64(buf: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) buf[off + i] = (v ushr ((7 - i) * 8)).toByte()
    }

    private fun put16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 8).toByte()
        buf[off + 1] = v.toByte()
    }

    fun injectKeycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0) {
        val buf = ByteArray(1 + 1 + 4 + 4 + 4)
        buf[0] = TYPE_INJECT_KEYCODE.toByte()
        buf[1] = action.toByte()
        put32(buf, 2, keycode)
        put32(buf, 6, repeat)
        put32(buf, 10, metaState)
        send(buf)
    }

    /** 触控注入: 坐标为受控设备视频坐标系, w/h 为当前视频尺寸 */
    fun injectTouch(action: Int, pointerId: Long, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pressure: Float) {
        val buf = ByteArray(1 + 1 + 8 + 4 + 4 + 2 + 2 + 4 + 4)
        var o = 0
        buf[o] = TYPE_INJECT_TOUCH_EVENT.toByte(); o += 1
        buf[o] = action.toByte(); o += 1
        put64(buf, o, pointerId); o += 8
        put32(buf, o, x); o += 4
        put32(buf, o, y); o += 4
        put16(buf, o, screenWidth and 0xFFFF); o += 2
        put16(buf, o, screenHeight and 0xFFFF); o += 2
        val p = if (pressure >= 1f) 0xFFFF else (pressure * 65536f).toInt()
        put16(buf, o, p); o += 2
        put32(buf, o, 0); o += 4 // actionButton
        put32(buf, o, 0)         // buttons (touch 必须 0)
        send(buf)
    }

    fun injectScroll(x: Int, y: Int, screenWidth: Int, screenHeight: Int, hScroll: Float, vScroll: Float) {
        val buf = ByteArray(1 + 4 + 4 + 2 + 2 + 2 + 2 + 4)
        var o = 0
        buf[o] = TYPE_INJECT_SCROLL_EVENT.toByte(); o += 1
        put32(buf, o, x); o += 4
        put32(buf, o, y); o += 4
        put16(buf, o, screenWidth and 0xFFFF); o += 2
        put16(buf, o, screenHeight and 0xFFFF); o += 2
        put16(buf, o, i16FixedPoint(hScroll).toInt() and 0xFFFF); o += 2
        put16(buf, o, i16FixedPoint(vScroll).toInt() and 0xFFFF); o += 2
        put32(buf, o, 0)
        send(buf)
    }

    fun backOrScreenOn(down: Boolean) {
        val buf = ByteArray(2)
        buf[0] = TYPE_BACK_OR_SCREEN_ON.toByte()
        buf[1] = (if (down) ACTION_DOWN else ACTION_UP).toByte()
        send(buf)
    }

    fun expandNotificationPanel() = send(byteArrayOf(TYPE_EXPAND_NOTIFICATION_PANEL.toByte()))
    fun collapsePanels() = send(byteArrayOf(TYPE_COLLAPSE_PANELS.toByte()))
    fun rotateDevice() = send(byteArrayOf(TYPE_ROTATE_DEVICE.toByte()))
}
