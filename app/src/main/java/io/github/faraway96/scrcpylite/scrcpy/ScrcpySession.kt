package io.github.faraway96.scrcpylite.scrcpy

import io.github.faraway96.scrcpylite.adb.AdbConnection
import java.io.ByteArrayOutputStream
import java.io.IOException

/** 镜像参数 */
data class MirrorParams(
    val maxSize: Int = 1600,
    val videoBitRate: Int = 4_000_000,
    val maxFps: Int = 30,
    val videoCodec: String = "h264"
)

/**
 * scrcpy 会话: 推送并启动 scrcpy-server, 建立 video/control 两条流。
 * 协议基于 scrcpy v2.7 (Genymobile/scrcpy), 全部大端序。
 */
class ScrcpySession(
    private val conn: AdbConnection,
    private val params: MirrorParams,
    private val serverBytes: ByteArray,
    private val log: (String) -> Unit
) {
    companion object {
        const val SERVER_PATH = "/data/local/tmp/scrcpylite-server-v2.7"
        const val SERVER_VERSION = "2.7"
        const val SOCKET_PREFIX = "scrcpy"

        // 帧头标志位
        const val PACKET_FLAG_CONFIG = 1L shl 63
        const val PACKET_FLAG_KEY_FRAME = 1L shl 62
    }

    var deviceName: String = ""
        private set
    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set

    lateinit var videoStream: io.github.faraway96.scrcpylite.adb.AdbStream
        private set
    lateinit var controlStream: io.github.faraway96.scrcpylite.adb.AdbStream
        private set

    private lateinit var execStream: io.github.faraway96.scrcpylite.adb.AdbStream
    private val logBuffer = StringBuilder()
    @Volatile var stopped = false
        private set

    /** 推送服务器二进制并校验大小 */
    fun pushServer() {
        log("推送 scrcpy-server (${serverBytes.size} 字节)...")
        val push = conn.open("exec:cat > $SERVER_PATH")
        push.write(serverBytes)
        Thread.sleep(300) // 等 adbd 把数据交给 cat
        push.close()

        val out = conn.open("shell:ls -l $SERVER_PATH").readUntilClose(8000)
        val text = String(out, Charsets.UTF_8)
        log("ls: ${text.trim()}")
        val sizeMatch = Regex("\\s(\\d+)\\s").findAll(text).map { it.groupValues[1].toLong() }.maxOrNull()
        if (sizeMatch != serverBytes.size.toLong()) {
            throw IOException("服务器文件校验失败: 期望 ${serverBytes.size}, 实际 $sizeMatch")
        }
    }

    /** 启动服务器 (exec 通道保持打开, 用于读取日志) */
    fun startServer() {
        val scid = kotlin.random.Random.nextInt(1, 0x7FFFFFFF)
        scidHex = String.format("%08x", scid)
        val args = buildString {
            append("CLASSPATH=$SERVER_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION")
            append(" log_level=warn")
            append(" scid=").append(scid.toString(16))
            append(" tunnel_forward=true")
            append(" video=true audio=false control=true")
            append(" send_dummy_byte=true send_frame_meta=true send_device_meta=true")
            append(" video_codec=").append(params.videoCodec)
            append(" max_size=").append(params.maxSize)
            append(" video_bit_rate=").append(params.videoBitRate)
            append(" max_fps=").append(params.maxFps)
            append(" cleanup=false")
        }
        log("启动 scrcpy-server...")
        execStream = conn.open("exec:$args")
        // 后台收集服务器日志
        Thread({
            try {
                while (!stopped) {
                    val d = execStream.read()
                    if (d.isEmpty()) break
                    val s = String(d, Charsets.UTF_8)
                    synchronized(logBuffer) {
                        logBuffer.append(s)
                        if (logBuffer.length > 4096) logBuffer.delete(0, logBuffer.length - 4096)
                    }
                }
            } catch (_: Exception) {}
        }, "server-log").start()
    }

    private var scidHex: String = ""

    private fun logTail(): String = synchronized(logBuffer) { logBuffer.toString() }

    /**
     * 打开 video/control 两条 localabstract 流。
     * 顺序: 第 1 条 = video (dummy byte + 64B 设备名 + 12B codec 元数据), 第 2 条 = control。
     */
    fun openSockets() {
        val socketName = "${SOCKET_PREFIX}_${scidHex}"
        log("连接 $socketName (等待服务器就绪)...")

        val deadline = System.currentTimeMillis() + 15_000
        videoStream = tryOpenWithRetry(socketName, deadline, "video")
        // video socket: dummy byte + 设备名(64B) + codec 元数据(12B)
        val dummy = readWithTimeout(videoStream, 1, 8000)
        if (dummy.size < 1) throw IOException("读取 dummy byte 失败\n${logTail()}")
        val meta = readWithTimeout(videoStream, 64, 8000)
        if (meta.size < 64) throw IOException("读取设备元数据失败\n${logTail()}")
        deviceName = String(meta, Charsets.US_ASCII).trim('\u0000')

        val codecMeta = readWithTimeout(videoStream, 12, 8000)
        if (codecMeta.size < 12) throw IOException("读取视频元数据失败\n${logTail()}")
        fun be32(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
        val codecId = be32(codecMeta, 0)
        videoWidth = be32(codecMeta, 4)
        videoHeight = be32(codecMeta, 8)
        log("设备: $deviceName 视频: ${videoWidth}x${videoHeight} codecId=$codecId")

        controlStream = tryOpenWithRetry(socketName, deadline, "control")
    }

    private fun tryOpenWithRetry(socketName: String, deadline: Long, what: String): io.github.faraway96.scrcpylite.adb.AdbStream {
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline && !stopped) {
            try {
                return conn.open("localabstract:$socketName")
            } catch (e: Exception) {
                last = e
                Thread.sleep(250)
            }
        }
        throw IOException("打开 $what 流失败: ${last?.message}\n服务器日志:\n${logTail()}")
    }

    private fun readWithTimeout(stream: io.github.faraway96.scrcpylite.adb.AdbStream, n: Int, timeoutMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size() < n && System.currentTimeMillis() < deadline) {
            if (stream.closed) throw IOException("流已关闭\n${logTail()}")
            val d = stream.read()
            if (d.isEmpty()) break
            out.write(d)
        }
        return out.toByteArray()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        try { controlStream.close() } catch (_: Exception) {}
        try { videoStream.close() } catch (_: Exception) {}
        try { execStream.close() } catch (_: Exception) {}
    }
}
