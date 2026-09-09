package io.github.faraway96.scrcpylite.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * ADB 协议常量与消息帧。
 * 帧结构 (24 字节): cmd[4] ASCII + arg0 u32LE + arg1 u32LE + dataLen u32LE + checksum u32LE + cmd[4]
 * checksum = payload 字节简单求和 (mod 2^32)
 */
object AdbProtocol {
    const val CNXN = "CNXN"
    const val AUTH = "AUTH"
    const val OPEN = "OPEN"
    const val OKAY = "OKAY"
    const val WRTE = "WRTE"
    const val CLSE = "CLSE"

    const val VERSION = 0x01000001

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    fun checksum(data: ByteArray, len: Int = data.size): Long {
        var sum = 0L
        for (i in 0 until len) sum += data[i].toLong() and 0xFFL
        return sum and 0xFFFFFFFFL
    }
}

class AdbMessage(
    val cmd: String,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray
) {
    companion object {
        fun readFrame(input: InputStream): AdbMessage {
            val dis = DataInputStream(input)
            val head = ByteArray(24)
            dis.readFully(head)
            val cmd = String(head, 0, 4, Charsets.US_ASCII)
            fun u32(off: Int): Int =
                ((head[off].toInt() and 0xFF) or
                 ((head[off + 1].toInt() and 0xFF) shl 8) or
                 ((head[off + 2].toInt() and 0xFF) shl 16) or
                 ((head[off + 3].toInt() and 0xFF) shl 24))
            val arg0 = u32(4)
            val arg1 = u32(8)
            val dataLen = u32(12)
            val crc = u32(16).toLong() and 0xFFFFFFFFL
            val tail = String(head, 20, 4, Charsets.US_ASCII)
            if (tail != cmd) throw IOException("ADB 帧损坏: magic mismatch $cmd != $tail")
            if (dataLen < 0 || dataLen > 16 * 1024 * 1024) throw IOException("ADB 帧长度异常: $dataLen")
            val data = if (dataLen > 0) ByteArray(dataLen).also { dis.readFully(it) } else ByteArray(0)
            if (AdbProtocol.checksum(data) != crc) throw IOException("ADB 帧校验和不匹配")
            return AdbMessage(cmd, arg0, arg1, data)
        }
    }

    fun write(output: OutputStream) {
        val out = DataOutputStream(output)
        val head = ByteArray(24)
        cmd.toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        fun put32(off: Int, v: Int) {
            head[off] = (v and 0xFF).toByte()
            head[off + 1] = ((v shr 8) and 0xFF).toByte()
            head[off + 2] = ((v shr 16) and 0xFF).toByte()
            head[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        put32(4, arg0)
        put32(8, arg1)
        put32(12, data.size)
        put32(16, AdbProtocol.checksum(data).toInt())
        cmd.toByteArray(Charsets.US_ASCII).copyInto(head, 20)
        out.write(head)
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }
}
