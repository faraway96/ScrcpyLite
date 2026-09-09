package io.github.faraway96.scrcpylite.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import io.github.faraway96.scrcpylite.adb.AdbStream

/**
 * 视频解码: 从 video 流读取帧 (12 字节头 + payload), MediaCodec 硬解直出 Surface。
 * 输入读取与输出抽取分两个线程, 避免慢设备上相互阻塞。
 */
class VideoDecoder(
    private val stream: AdbStream,
    private val surface: Surface,
    private val codecName: String,
    private val initialSize: Pair<Int, Int>,
    private val onSizeChanged: (Int, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile var stopped = false
        private set

    // 当前视频尺寸 (旋转后随 format change 更新), 供触控映射
    @Volatile var videoWidth = initialSize.first
        private set
    @Volatile var videoHeight = initialSize.second
        private set

    private var codec: MediaCodec? = null
    private val lock = Object()

    private fun readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val d = stream.read()
            if (d.isEmpty()) throw java.io.IOException("视频流结束")
            val copy = minOf(n - off, d.size)
            System.arraycopy(d, 0, out, off, copy)
            off += copy
        }
        return out
    }

    fun start() {
        val inputThread = Thread({ inputLoop() }, "codec-input")
        val outputThread = Thread({ outputLoop() }, "codec-output")
        inputThread.start()
        outputThread.start()
    }

    private fun inputLoop() {
        try {
            var codec: MediaCodec? = null
            var configData: ByteArray? = null
            var started = false

            while (!stopped) {
                // 1. 若有已取出的 input buffer 且数据已就绪则填入
                // 读取 12 字节帧头
                val header = readFully(12)
                fun be64(b: ByteArray): Long {
                    var v = 0L
                    for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
                    return v
                }
                fun be32(b: ByteArray, off: Int): Int =
                    ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                    ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

                val ptsAndFlags = be64(header)
                val pts = ptsAndFlags and PACKET_FLAG_CONFIG.inv() and PACKET_FLAG_KEY_FRAME.inv()
                val isConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
                val len = be32(header, 8)
                if (len <= 0 || len > 8 * 1024 * 1024) throw java.io.IOException("非法帧长度: $len")
                val payload = readFully(len)

                if (!started) {
                    if (!isConfig) throw java.io.IOException("首帧不是 codec 配置帧")
                    configData = payload
                    codec = MediaCodec.createDecoderByType(codecName)
                    val format = MediaFormat.createVideoFormat(codecName, videoWidth, videoHeight)
                    codec.configure(format, surface, null, 0)
                    codec.start()
                    this.codec = codec
                    started = true
                }

                // 取一个 input buffer
                val inIdx = codec!!.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    ib.clear()
                    ib.put(payload)
                    val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    codec.queueInputBuffer(inIdx, 0, payload.size, if (isConfig) 0 else pts, flags)
                }
            }
        } catch (e: Exception) {
            if (!stopped) onError("视频输入: ${e.message}")
        }
    }

    private fun outputLoop() {
        try {
            // 等待 inputLoop 创建 codec
            var c: MediaCodec? = null
            while (!stopped) {
                c = codec
                if (c != null) break
                Thread.sleep(20)
            }
            val info = MediaCodec.BufferInfo()
            while (!stopped) {
                val cc = c ?: break
                val idx = cc.dequeueOutputBuffer(info, 10_000)
                when {
                    idx >= 0 -> {
                        cc.releaseOutputBuffer(idx, true)
                        // 从输出格式捕获尺寸变化 (旋转)
                        // info.presentationTimeUs 已由 release 使用
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = cc.outputFormat
                        val w = f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                            videoWidth = w
                            videoHeight = h
                            onSizeChanged(w, h)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped) onError("视频输出: ${e.message}")
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }
}
