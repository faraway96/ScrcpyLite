package io.github.faraway96.scrcpylite

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.faraway96.scrcpylite.adb.AdbConnection
import io.github.faraway96.scrcpylite.scrcpy.ControlWriter
import io.github.faraway96.scrcpylite.scrcpy.MirrorParams
import io.github.faraway96.scrcpylite.scrcpy.ScrcpySession
import io.github.faraway96.scrcpylite.scrcpy.VideoDecoder
import java.io.IOException

class MirrorActivity : Activity() {

    private lateinit var host: String
    private var port: Int = 5555
    private lateinit var params: MirrorParams

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var bar: LinearLayout

    private var conn: AdbConnection? = null
    private var session: ScrcpySession? = null
    private var decoder: VideoDecoder? = null
    private var control: ControlWriter? = null

    private val setupLock = Any()
    private var setupDone = false
    private var surfaceReady = false

    // 当前视频尺寸 (触控映射基准)
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    private val uiStatus: (String) -> Unit = { s ->
        runOnUiThread {
            statusText.text = if (s.isEmpty()) "● 在线 ${videoW}x${videoH}" else s
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_mirror)

        host = intent.getStringExtra("host") ?: "127.0.0.1"
        port = intent.getIntExtra("port", 5555)
        params = MirrorParams(
            maxSize = intent.getIntExtra("maxSize", 1600),
            videoBitRate = intent.getIntExtra("bitrate", 4_000_000),
            maxFps = intent.getIntExtra("fps", 30)
        )

        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.sv_video)
        statusText = findViewById(R.id.tv_mirror_status)
        bar = findViewById(R.id.bar)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                maybeStart()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        setupControls()
        setupTouch()
    }

    private fun setupControls() {
        fun tap(id: Int, action: () -> Unit) {
            findViewById<Button>(id).setOnClickListener {
                try {
                    action()
                } catch (e: Exception) {
                    Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        tap(R.id.btn_back) {
            control?.backOrScreenOn(true)
            control?.backOrScreenOn(false)
        }
        tap(R.id.btn_home) {
            control?.injectKeycode(ControlWriter.ACTION_DOWN, 3)
            control?.injectKeycode(ControlWriter.ACTION_UP, 3)
        }
        tap(R.id.btn_recent) {
            control?.injectKeycode(ControlWriter.ACTION_DOWN, 187)
            control?.injectKeycode(ControlWriter.ACTION_UP, 187)
        }
        tap(R.id.btn_rotate) { control?.rotateDevice() }
        tap(R.id.btn_exit) { finish() }
    }

    private fun setupTouch() {
        var lastX = 0f
        var lastY = 0f
        surfaceView.setOnTouchListener { v, ev ->
            if (control == null || videoW == 0 || videoH == 0) return@setOnTouchListener false

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    for (i in 0 until ev.pointerCount) {
                        val (x, y) = mapToVideo(ev.getX(i), ev.getY(i))
                        control?.injectTouch(ControlWriter.ACTION_DOWN, ev.getPointerId(i).toLong(), x, y, videoW, videoH, ev.pressure.coerceAtLeast(0.5f))
                    }
                    lastX = ev.getX(0); lastY = ev.getY(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount >= 2) {
                        // 双指滚动
                        val dy = (ev.getY(0) - lastY) + (ev.getY(1) - lastY)
                        val dx = (ev.getX(0) - lastX) + (ev.getX(1) - lastX)
                        if (kotlin.math.abs(dy) > 2f || kotlin.math.abs(dx) > 2f) {
                            val (cx, cy) = mapToVideo(ev.getX(0), ev.getY(0))
                            control?.injectScroll(cx, cy, videoW, videoH, (dx / 60f).coerceIn(-4f, 4f), (dy / 60f).coerceIn(-4f, 4f))
                            lastX = ev.getX(0); lastY = ev.getY(0)
                        }
                    } else {
                        for (i in 0 until ev.pointerCount) {
                            val (x, y) = mapToVideo(ev.getX(i), ev.getY(i))
                            control?.injectTouch(ControlWriter.ACTION_MOVE, ev.getPointerId(i).toLong(), x, y, videoW, videoH, ev.pressure.coerceAtLeast(0.5f))
                        }
                        lastX = ev.getX(0); lastY = ev.getY(0)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    for (i in 0 until ev.pointerCount) {
                        val (x, y) = mapToVideo(ev.getX(i), ev.getY(i))
                        control?.injectTouch(ControlWriter.ACTION_UP, ev.getPointerId(i).toLong(), x, y, videoW, videoH, 0f)
                    }
                }
            }
            true
        }
    }

    /** 把本机触点坐标映射到受控设备视频坐标 (等比留黑) */
    private fun mapToVideo(rx: Float, ry: Float): Pair<Int, Int> {
        val sw = surfaceView.width.toFloat()
        val sh = surfaceView.height.toFloat()
        if (sw <= 0 || sh <= 0 || videoW == 0 || videoH == 0) return 0 to 0
        val scale = minOf(sw / videoW, sh / videoH)
        val dispW = videoW * scale
        val dispH = videoH * scale
        val ox = (sw - dispW) / 2f
        val oy = (sh - dispH) / 2f
        val x = ((rx - surfaceView.left - ox) / scale).toInt().coerceIn(0, videoW - 1)
        val y = ((ry - surfaceView.top - oy) / scale).toInt().coerceIn(0, videoH - 1)
        return x to y
    }

    /** 按视频宽高比调整 SurfaceView 尺寸 (等比, 居中) */
    private fun fitSurface(vw: Int, vh: Int) {
        runOnUiThread {
            val rootW = root.width
            val rootH = root.height
            if (rootW == 0 || rootH == 0 || vw == 0 || vh == 0) return@runOnUiThread
            val scale = minOf(rootW.toFloat() / vw, rootH.toFloat() / vh)
            val w = (vw * scale).toInt()
            val h = (vh * scale).toInt()
            surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
            surfaceView.requestLayout()
            uiStatus("")
        }
    }

    private fun maybeStart() {
        synchronized(setupLock) {
            if (setupDone || !surfaceReady) return
            setupDone = true
        }
        Thread({
            try {
                runSession()
            } catch (e: Exception) {
                runOnUiThread { showError(e.message ?: e.javaClass.simpleName) }
            }
        }, "session-setup").start()
    }

    private fun runSession() {
        uiStatus("连接 $host:$port ...")
        val connection = AdbConnection(host, port)
        conn = connection
        connection.connect(filesDir) { needConsent ->
            if (needConsent) uiStatus("📱 请在被控设备上点击「允许 USB 调试」...")
        }
        uiStatus("已连接, 校验设备...")

        val serverBytes = assets.open("scrcpy-server-v2.7").use { it.readBytes() }
        val session = ScrcpySession(connection, params, serverBytes) { s -> uiStatus(s) }
        this.session = session
        session.pushServer()
        session.startServer()
        session.openSockets()

        videoW = session.videoWidth
        videoH = session.videoHeight
        control = ControlWriter(session.controlStream)
        fitSurface(videoW, videoH)

        // 等待 surface 有效
        while (!surfaceReady && !isFinishing) Thread.sleep(50)

        val holder: SurfaceHolder = surfaceView.holder
        val surface: Surface = holder.surface
        val decoder = VideoDecoder(
            session.videoStream, surface, "video/avc",
            videoW to videoH,
            onSizeChanged = { w, h ->
                videoW = w; videoH = h
                fitSurface(w, h)
            },
            onError = { msg ->
                runOnUiThread { showError(msg) }
            }
        )
        this.decoder = decoder
        decoder.start()
        uiStatus("")
    }

    private fun showError(message: String) {
        if (isFinishing) return
        statusText.text = "错误"
        AlertDialog.Builder(this)
            .setTitle("连接中断")
            .setMessage(message)
            .setPositiveButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ---- 全屏沉浸 ----
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    // ---- 实体键映射到被控设备 ----
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { control?.injectKeycode(ControlWriter.ACTION_DOWN, 24); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { control?.injectKeycode(ControlWriter.ACTION_DOWN, 25); return true }
            KeyEvent.KEYCODE_BACK -> { control?.backOrScreenOn(true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { control?.injectKeycode(ControlWriter.ACTION_UP, 24); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { control?.injectKeycode(ControlWriter.ACTION_UP, 25); return true }
            KeyEvent.KEYCODE_BACK -> { control?.backOrScreenOn(false); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        Thread({
            try { decoder?.stop() } catch (_: Exception) {}
            try { session?.stop() } catch (_: Exception) {}
            try { conn?.close() } catch (_: Exception) {}
        }, "session-cleanup").start()
    }
}
