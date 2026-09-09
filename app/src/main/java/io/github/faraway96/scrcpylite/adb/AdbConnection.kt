package io.github.faraway96.scrcpylite.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 一条 ADB 流 (对应一次 OPEN) */
class AdbStream internal constructor(
    val localId: Int,
    private val conn: AdbConnection
) {
    val remoteId = AtomicInteger(0)
    private val connectedLatch = CountDownLatch(1)
    private val closedFlag = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile var closed = false
        private set
    @Volatile var openFailed: Boolean = false

    internal fun onOkay(remote: Int) {
        remoteId.set(remote)
        connectedLatch.countDown()
    }

    internal fun onData(data: ByteArray) {
        if (!closed) queue.add(data)
    }

    internal fun onClose() {
        if (closedFlag.compareAndSet(false, true)) {
            closed = true
            connectedLatch.countDown()
            queue.add(ByteArray(0)) // 唤醒阻塞的读取
        }
    }

    /** 等待对端 OKAY; 返回 false 表示打开失败或被关闭 */
    fun awaitOpen(timeoutMs: Long): Boolean {
        connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return !closed && remoteId.get() != 0
    }

    /** 分块写入 (自动按连接 maxPayload 切分) */
    fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val len = minOf(conn.maxPayload, data.size - off)
            val chunk = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
            conn.send(AdbMessage(AdbProtocol.WRTE, localId, remoteId.get(), chunk))
            off += len
        }
    }

    /** 阻塞读取一段数据; 返回空数组表示流已关闭且无数据 */
    fun read(): ByteArray {
        while (true) {
            if (closed && queue.isEmpty()) return ByteArray(0)
            val d = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (d.isEmpty() && closed && queue.isEmpty()) return ByteArray(0)
            if (d.isNotEmpty()) return d
        }
    }

    /** 读取直到流关闭, 返回全部输出 (用于 shell/exec 结果) */
    fun readUntilClose(timeoutMs: Long = 15000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!closed && System.currentTimeMillis() < deadline) {
            val d = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (d.isNotEmpty()) out.write(d)
        }
        return out.toByteArray()
    }

    fun close() {
        if (closedFlag.compareAndSet(false, true)) {
            closed = true
            try {
                conn.send(AdbMessage(AdbProtocol.CLSE, localId, remoteId.get(), ByteArray(0)))
            } catch (_: Exception) {}
            conn.removeStream(localId)
        }
    }
}

/** 与受控设备 adbd 的单条 TCP 连接 (设备侧 transport), 支持多路复用 */
class AdbConnection(private val host: String, private val port: Int) {
    private val socket = Socket()
    private lateinit var input: BufferedInputStream
    private lateinit var output: BufferedOutputStream
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val writeLock = Any()
    private val readerThread = Thread({ readerLoop() }, "adb-reader")

    @Volatile var maxPayload = 4096
        private set
    @Volatile var deviceInfo: String = ""
        private set
    @Volatile var authorized = false
        private set

    /**
     * 建立连接并完成 RSA 认证。
     * @param authCallback (needUserConsent) 认证过程中回调, true 表示已发送公钥等待设备端弹窗确认
     * @return 设备信息字符串 "device::ro.product..."
     */
    fun connect(filesDir: java.io.File, authCallback: ((Boolean) -> Unit)? = null): String {
        AdbKeys.loadOrCreate(filesDir)
        socket.connect(InetSocketAddress(host, port), 8000)
        // 握手期间允许等待用户在设备上点击授权弹窗
        socket.soTimeout = 120_000
        input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

        send(AdbMessage(AdbProtocol.CNXN, AdbProtocol.VERSION, 1024 * 1024, "host::scrcpylite".toByteArray(Charsets.US_ASCII)))

        var pubkeySent = false
        var tokenCount = 0
        // 握手循环: 直到收到 CNXN (授权完成) 或超时
        val deadline = System.currentTimeMillis() + 120_000
        while (authorized.not()) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) throw IOException("认证超时: 请在受控设备上确认 USB 调试授权弹窗")
            val msg = try {
                AdbMessage.readFrame(input)
            } catch (e: IOException) {
                throw IOException("连接被重置: ${e.message}")
            }
            when (msg.cmd) {
                AdbProtocol.CNXN -> {
                    maxPayload = msg.arg1
                    authorized = true
                    deviceInfo = String(msg.data, Charsets.US_ASCII)
                    socket.soTimeout = 0
                    readerThread.start() // 握手完成后才开始多路复用读循环, 避免与主线程抢字节
                }
                AdbProtocol.AUTH -> {
                    tokenCount++
                    when (msg.arg0) {
                        AdbProtocol.AUTH_TOKEN -> {
                            if (tokenCount == 1) {
                                // 先尝试签名
                                send(AdbMessage(AdbProtocol.AUTH, AdbProtocol.AUTH_SIGNATURE, 0, AdbKeys.sign(msg.data)))
                            } else {
                                if (!pubkeySent) {
                                    pubkeySent = true
                                    authCallback?.invoke(true)
                                    send(AdbMessage(AdbProtocol.AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, AdbKeys.publicKeyPayload()))
                                }
                                send(AdbMessage(AdbProtocol.AUTH, AdbProtocol.AUTH_SIGNATURE, 0, AdbKeys.sign(msg.data)))
                            }
                        }
                        else -> throw IOException("未知 AUTH 类型: ${msg.arg0}")
                    }
                }
                else -> throw IOException("握手阶段收到非预期消息: ${msg.cmd}")
            }
        }
        return deviceInfo
    }

    /** 打开一条流 (destination 形如 "exec:...", "shell:...", "localabstract:...") */
    fun open(destination: String): AdbStream {
        val id = nextLocalId.getAndIncrement()
        val stream = AdbStream(id, this)
        streams[id] = stream
        send(AdbMessage(AdbProtocol.OPEN, id, 0, destination.toByteArray(Charsets.US_ASCII)))
        if (!stream.awaitOpen(10_000)) {
            streams.remove(id)
            stream.openFailed = true
            throw IOException("打开流失败: $destination")
        }
        return stream
    }

    fun removeStream(localId: Int) {
        streams.remove(localId)
    }

    fun send(msg: AdbMessage) {
        synchronized(writeLock) {
            msg.write(output)
        }
    }

    fun close() {
        try {
            streams.values.forEach { it.close() }
        } catch (_: Exception) {}
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    private fun readerLoop() {
        try {
            while (!socket.isClosed) {
                val msg = AdbMessage.readFrame(input)
                val stream = streams[msg.arg1]
                when (msg.cmd) {
                    AdbProtocol.OKAY -> stream?.onOkay(msg.arg0)
                    AdbProtocol.WRTE -> {
                        stream?.onData(msg.data)
                        // 每个收到的 WRTE 必须回一个 OKAY
                        send(AdbMessage(AdbProtocol.OKAY, msg.arg1, msg.arg0, ByteArray(0)))
                    }
                    AdbProtocol.CLSE -> stream?.onClose()
                }
            }
        } catch (_: Exception) {
            // socket 关闭/出错: 标记全部流关闭
            streams.values.forEach { it.onClose() }
        }
    }

    companion object {
        private val nextLocalId = java.util.concurrent.atomic.AtomicInteger(1)
    }
}
