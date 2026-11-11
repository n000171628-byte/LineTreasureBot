package com.example.linebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 負責在螢幕上模擬點擊。使用者需到「設定 > 無障礙」手動開啟。 */
class ClickService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 點擊螢幕座標 (x, y)，單位為像素。 */
    fun tap(x: Float, y: Float, durationMs: Long = 50): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * 點擊並等到系統回報手勢完成（或逾時 800 ms）。只能在背景執行緒呼叫，不可在主執行緒呼叫。
     * 回傳 true 代表手勢有完整送出；遊戲有沒有吃到這一下要另外看畫面。
     */
    fun tapBlocking(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                done.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, null)
        if (!sent) return false
        latch.await(800, TimeUnit.MILLISECONDS)
        return done.get()
    }

    companion object {
        @Volatile
        var instance: ClickService? = null
            private set
    }
}
