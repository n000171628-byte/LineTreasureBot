package com.example.linebot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/** 使用 MediaProjection 取得整個螢幕的截圖。 */
class ScreenCapturer(private val context: Context, private val projection: MediaProjection) {

    // 用含導覽列的真實螢幕尺寸，否則截圖會被壓扁、點擊座標也會偏
    @Suppress("DEPRECATION")
    private val metrics = DisplayMetrics().also {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }
    val width = metrics.widthPixels
    val height = metrics.heightPixels

    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay

    init {
        // Android 14 起必須先註冊 callback 才能 createVirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay(
            "bot-capture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    @Volatile
    private var last: Bitmap? = null

    /**
     * 回傳最新畫面。螢幕內容沒變時系統不會送新影格，所以沒有新畫面就回傳上一張；
     * 還沒收到任何畫面時回傳 null。
     */
    fun capture(): Bitmap? {
        val image = reader.acquireLatestImage() ?: return last
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val padded = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (cropped !== padded) padded.recycle()
            last = cropped
            return cropped
        } finally {
            image.close()
        }
    }

    fun release() {
        display.release()
        reader.close()
        projection.stop()
    }
}
