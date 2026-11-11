package com.example.linebot

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val svc = Intent(this, BotService::class.java)
            .putExtra(BotService.EXTRA_CODE, result.resultCode)
            .putExtra(BotService.EXTRA_DATA, result.data)
        startForegroundService(svc)
        moveTaskToBack(true) // 回到遊戲
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
        root.addView(button("1. 允許顯示在其他應用程式上層") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. 開啟無障礙服務（點擊用）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("3. 授權截圖並啟動懸浮按鈕") {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        })
        root.addView(button("分享除錯檔（log + 截圖）") { shareDebug() })
        root.addView(button("停止服務") { stopService(Intent(this, BotService::class.java)) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val click = if (ClickService.instance != null) "✅" else "❌"
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        status.text = "版本 $ver\n懸浮視窗權限 $overlay\n無障礙服務 $click"
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    /** 把 debug 資料夾裡的 log.txt 與 last.jpg 用系統分享選單送出（免找 Android/data 資料夾）。 */
    private fun shareDebug() {
        val dir = File(getExternalFilesDir(null), "debug")
        val files = listOf("log.txt", "last.jpg").map { File(dir, it) }.filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) {
            Toast.makeText(this, "還沒有除錯檔：請先按懸浮列的「開始」跑一小段", Toast.LENGTH_LONG).show()
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享除錯檔"))
    }
}
