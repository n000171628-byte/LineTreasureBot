package com.example.linebot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * 前景服務：顯示懸浮控制列（☰ 拖曳、開始、暫停、狀態文字）。
 * 狀態文字「執行·N」的 N 是目前偵測到的可點亮牌數量，可用來確認辨識有在運作。
 * 偵測結果會存成 Android/data/com.example.linebot/files/debug/last.jpg 方便除錯。
 */
class BotService : Service() {

    private var capturer: ScreenCapturer? = null
    private var loop: BotLoop? = null
    private var overlay: View? = null
    private var startBtn: Button? = null
    private var pauseBtn: Button? = null
    private var statusView: TextView? = null
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e(TAG, "OpenCV init failed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)

        if (resultCode == Activity.RESULT_OK && data != null && capturer == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            capturer = ScreenCapturer(this, mpm.getMediaProjection(resultCode, data))
            if (Settings.canDrawOverlays(this)) showOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC222222.toInt())
            setPadding(pad(4), pad(4), pad(4), pad(4))
        }
        val handle = TextView(this).apply {
            text = "☰"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(pad(8), pad(4), pad(12), pad(4))
        }
        startBtn = Button(this).apply { text = "開始"; textSize = 14f; minWidth = 0; minimumWidth = 0 }
        pauseBtn = Button(this).apply { text = "暫停"; textSize = 14f; minWidth = 0; minimumWidth = 0; isEnabled = false }
        statusView = TextView(this).apply {
            text = "v" + versionName() + " 待機"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(pad(8), 0, pad(8), 0)
        }
        bar.addView(handle)
        bar.addView(startBtn)
        bar.addView(pauseBtn)
        bar.addView(statusView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        // 只有左邊的 ☰ 可以拖曳，避免拖曳時誤按開始／暫停
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(bar, params)
                }
            }
            true
        }
        startBtn?.setOnClickListener { startLoop() }
        pauseBtn?.setOnClickListener { pauseLoop() }

        wm.addView(bar, params)
        overlay = bar
    }

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun startLoop() {
        if (loop?.isAlive == true) return
        // 重新安裝 APK 後，系統會自動關掉無障礙服務，必須重新開啟
        if (ClickService.instance == null) {
            statusView?.text = "請開無障礙"
            Toast.makeText(this, "無障礙服務沒開：設定 > 無障礙 > LINE秘寶助手點擊服務 > 開啟", Toast.LENGTH_LONG).show()
            return
        }
        val cap = capturer
        if (cap == null) {
            statusView?.text = "未授權截圖"
            return
        }
        val debugDir = File(getExternalFilesDir(null), "debug")
        loop = BotLoop(cap, debugDir) { msg -> main.post { statusView?.text = msg } }.also { it.start() }
        startBtn?.isEnabled = false
        pauseBtn?.isEnabled = true
        statusView?.text = "啟動中"
    }

    private fun pauseLoop() {
        loop?.stopLoop()
        loop = null
        startBtn?.isEnabled = true
        pauseBtn?.isEnabled = false
        statusView?.text = "已暫停"
    }

    private fun buildNotification(): Notification {
        val channelId = "bot"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "助手", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channelId)
            .setContentTitle("LINE秘寶助手執行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        loop?.stopLoop()
        overlay?.let { wm.removeView(it) }
        capturer?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BotService"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}
