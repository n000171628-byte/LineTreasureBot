package com.example.linebot

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.linebot.solver.Solver
import com.example.linebot.solver.Tile
import com.example.linebot.vision.DetectedTile
import com.example.linebot.vision.TileVision
import com.example.linebot.vision.TypeRegistry
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * 主循環：截圖 → 等畫面穩定 → 找亮牌 + 讀底下格子 → 解題規劃（一次最多 3 張）→ 逐張點擊並確認 → 重複。
 *
 * 重點：
 * - 點完之後牌會飛進格子、三消會有消除動畫。動畫中讀到的格子內容是錯的，所以每次決策前，
 *   要連續兩個畫面的「場上牌位置 + 格子內容」都一樣才算穩定。
 * - 每一下點擊都用前後畫面的差異確認牌真的被點掉，沒點到會重點一次。
 * - 換關（牌大小變、牌數突然變多）會清掉圖案分組與格子記錄。
 * - 格子內容優先從畫面讀取；讀不到時才用程式自己模擬。
 * 每一輪的紀錄會寫到 debug/log.txt，畫面標示存在 debug/last.jpg。
 */
class BotLoop(
    private val capturer: ScreenCapturer,
    private val debugDir: File,
    private val onStatus: (String) -> Unit
) : Thread("bot-loop") {

    private enum class SlotMode { UNKNOWN, RELIABLE, UNRELIABLE }

    @Volatile
    private var running = true

    private val solver = Solver()
    private val registry = TypeRegistry()
    private var slot = ArrayList<Int>()
    private var slotBeforeTaps = ArrayList<Int>()
    private var slotMode = SlotMode.UNKNOWN
    private var emptyReadStreak = 0
    private var emptyFrames = 0
    private var nullStreak = 0
    private var iteration = 0
    private var noFrame = 0
    private var lastTapped = false
    private var settleKey: String? = null
    private var settleTries = 0
    private var prevTileW = 0
    private var prevTileCount = 0
    private val logFile = File(debugDir, "log.txt")

    fun stopLoop() {
        running = false
        interrupt()
    }

    override fun run() {
        debugDir.mkdirs()
        onStatus("啟動中")
        log("start")
        var errors = 0
        while (running) {
            try {
                step()
                errors = 0
            } catch (ie: InterruptedException) {
                break
            } catch (e: Throwable) {
                errors++
                log("error #$errors: " + Log.getStackTraceString(e))
                onStatus("錯誤:" + e.javaClass.simpleName)
                if (errors >= 20) {
                    onStatus("已停止:連續錯誤")
                    break
                }
                try {
                    sleep(500)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
        registry.clear()
        log("stop")
    }

    private fun step() {
        val bmp = capturer.capture()
        if (bmp == null) {
            // 還沒收到任何畫面（螢幕沒有變化時系統不會送影格）
            noFrame++
            if (noFrame % 5 == 1) {
                onStatus("等待畫面")
                log("no frame yet x$noFrame")
            }
            sleep(300)
            return
        }
        noFrame = 0
        val rgba = Mat()
        var tiles: List<DetectedTile> = emptyList()
        var slotTiles: List<Pair<Int, DetectedTile>> = emptyList()
        val delay: Long
        try {
            Utils.bitmapToMat(bmp, rgba)
            tiles = TileVision.detect(rgba)
            slotTiles = TileVision.detectSlot(rgba)
            delay = process(rgba, tiles, slotTiles)
        } finally {
            tiles.forEach { it.release() }
            slotTiles.forEach { it.second.release() }
            rgba.release()
        }
        sleep(delay)
    }

    /** 換關或結束時，清掉這一關累積的所有狀態。 */
    private fun resetLevel() {
        registry.clear()
        slot.clear()
        slotMode = SlotMode.UNKNOWN
        emptyReadStreak = 0
        lastTapped = false
        settleKey = null
        settleTries = 0
        nullStreak = 0
    }

    /** 處理一個畫面，回傳之後要等多久（毫秒）。 */
    private fun process(rgba: Mat, tiles: List<DetectedTile>, slotTiles: List<Pair<Int, DetectedTile>>): Long {
        if (tiles.isEmpty()) {
            emptyFrames++
            if (emptyFrames >= 6) { // 沒牌 = 關卡結束或廣告，下一關重新開始
                resetLevel()
                prevTileW = 0
                prevTileCount = 0
            }
            onStatus("無牌")
            if (emptyFrames == 1 || emptyFrames % 10 == 0) saveDebug(rgba, tiles, IntArray(0), slotTiles)
            return 600
        }
        emptyFrames = 0

        // 換關偵測：牌大小變了，或牌數突然變多
        val tw = TileVision.lastTileWidth
        if (prevTileW != 0 && (Math.abs(tw - prevTileW) > prevTileW * 0.06 || tiles.size > prevTileCount + 8)) {
            log("new level: tileW $prevTileW -> $tw, tiles $prevTileCount -> ${tiles.size}")
            resetLevel()
            return 700 // 新關開場動畫中，等一下再點
        }
        prevTileW = tw
        prevTileCount = tiles.size

        val sig = tiles.map { (it.centerX / 12) * 10000 + it.centerY / 12 }.sorted()
        val slotSorted = slotTiles.sortedBy { it.first }

        // 點完之後等畫面穩定：連續兩個畫面的「牌位置 + 格子內容」一樣才決策
        if (lastTapped) {
            val key = sig.joinToString(",") + "|" +
                slotSorted.joinToString(",") { "${it.first}:${registry.matchKnown(it.second) ?: -1}" }
            if (key != settleKey && settleTries < MAX_SETTLE) {
                settleKey = key
                settleTries++
                return SETTLE_POLL_MS
            }
            if (settleTries >= MAX_SETTLE) log("screen still changing after $settleTries polls, deciding anyway")
        }
        settleKey = null
        settleTries = 0
        lastTapped = false

        // 先登記場上的牌，再讀格子；格子裡比不到已知圖案的牌，是這一關新出現的圖案，直接登記成新種類
        val types = IntArray(tiles.size) { registry.typeOf(tiles[it]) }
        val readInfo = StringBuilder()
        val read = slotSorted.map {
            val m = registry.matchKnown(it.second)
            val score = registry.lastScore
            val id = m ?: registry.typeOf(it.second)
            readInfo.append("s${it.first}->$id(${"%.2f".format(score)}${if (m == null) " new" else ""}) ")
            id
        }

        updateSlot(read)

        val plan = solver.plan(tiles.indices.map { Tile(it, types[it], tiles[it].unlock) }, slot, MAX_TAPS)
        if (iteration++ % 15 == 0) saveDebug(rgba, tiles, types, slotTiles)

        if (plan.isEmpty()) {
            nullStreak++
            if (slotMode != SlotMode.RELIABLE && slot.isNotEmpty() && nullStreak >= 2) {
                log("no safe move with simulated slot=$slot -> reset simulation")
                slot.clear()
                nullStreak = 0
            }
            onStatus("無安全步驟")
            log("no plan: tiles=${tiles.size} slot=$slot mode=$slotMode")
            saveDebug(rgba, tiles, types, slotTiles)
            return 1200
        }
        nullStreak = 0

        slotBeforeTaps = ArrayList(slot)
        var done = 0
        for (p in plan) {
            val t = tiles[p.id]
            if (!tapAndVerify(t)) {
                log("tap failed at ${t.centerX},${t.centerY} type=${p.type}")
                break
            }
            slot.add(p.type)
            val cleared = slot.count { it == p.type } == 3
            if (cleared) slot.removeAll { it == p.type }
            done++
            if (cleared) break // 每完成一次三消就停下來，重新讀畫面、重新推理
        }
        lastTapped = true
        onStatus("執行·${tiles.size}")
        log("tiles=${tiles.size} tileW=$tw kinds=${registry.size} slot=$slotBeforeTaps read=[$readInfo] plan=${plan.map { it.type }} done=$done")
        return SETTLE_POLL_MS
    }

    /** 點一張牌，用前後畫面的差異確認牌真的被點掉；沒點到就重點一次。 */
    private fun tapAndVerify(t: DetectedTile): Boolean {
        val svc = ClickService.instance ?: return false
        for (attempt in 1..2) {
            val before = capturer.capture() ?: return false
            svc.tapBlocking(t.centerX.toFloat(), t.centerY.toFloat(), if (attempt == 1) TAP_MS else RETRY_TAP_MS)
            val deadline = System.currentTimeMillis() + VERIFY_MS
            while (System.currentTimeMillis() < deadline) {
                sleep(30)
                val after = capturer.capture() ?: continue
                if (regionChanged(before, after, t)) {
                    sleep(TAP_GAP_MS) // 給遊戲一點時間，才吃得到下一下
                    return true
                }
            }
            log("tap not confirmed at ${t.centerX},${t.centerY} (attempt $attempt)")
            // 畫面差異小不代表沒點到（例如底下露出同色牌）。該位置已不是亮牌就當作成功，避免重點到別張牌
            val now = capturer.capture()
            if (now != null && !stillCream(now, t)) return true
            sleep(120) // 可能還在動畫中，等一下再重點
        }
        return false
    }

    /** 牌所在位置的 5x5 取樣點，前後畫面的平均色差是否明顯（牌被點掉後，這個區域會變成背景）。 */
    private fun regionChanged(a: Bitmap, b: Bitmap, t: DetectedTile): Boolean {
        if (a === b) return false
        var sum = 0L
        var n = 0
        for (iy in 0 until 5) {
            for (ix in 0 until 5) {
                val x = (t.x + t.w * (0.15 + 0.7 * ix / 4.0)).toInt().coerceIn(0, a.width - 1)
                val y = (t.y + t.h * (0.15 + 0.7 * iy / 4.0)).toInt().coerceIn(0, a.height - 1)
                if (x >= b.width || y >= b.height) continue
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                sum += Math.abs(Color.red(pa) - Color.red(pb)) +
                    Math.abs(Color.green(pa) - Color.green(pb)) +
                    Math.abs(Color.blue(pa) - Color.blue(pb))
                n++
            }
        }
        return n > 0 && sum.toDouble() / (n * 3) > CHANGE_THRESHOLD
    }

    /** 牌位置中央是否仍是米白色亮牌。 */
    private fun stillCream(b: Bitmap, t: DetectedTile): Boolean {
        var hit = 0
        var n = 0
        for (iy in 0 until 5) for (ix in 0 until 5) {
            val x = (t.x + t.w * (0.2 + 0.6 * ix / 4.0)).toInt().coerceIn(0, b.width - 1)
            val y = (t.y + t.h * (0.2 + 0.6 * iy / 4.0)).toInt().coerceIn(0, b.height - 1)
            val p = b.getPixel(x, y)
            val r = Color.red(p); val g = Color.green(p); val bl = Color.blue(p)
            if (r > 205 && g > 190 && bl > 130 && r - bl > 15) hit++
            n++
        }
        return hit >= n * 0.5
    }

    private fun updateSlot(read: List<Int>) {
        if (slotMode == SlotMode.UNRELIABLE) return
        if (read.isNotEmpty()) {
            if (slotMode != SlotMode.RELIABLE) log("slot readable from screen: $read")
            slotMode = SlotMode.RELIABLE
            slot = ArrayList(read)
            emptyReadStreak = 0
        } else if (slotMode == SlotMode.RELIABLE) {
            slot.clear() // 讀得到格子時，空就是空
        } else if (slot.isNotEmpty()) {
            emptyReadStreak++
            if (emptyReadStreak >= 3) {
                slotMode = SlotMode.UNRELIABLE
                log("slot not readable -> using simulation")
            }
        }
    }

    /** 把偵測結果畫在截圖上存起來：紅框 = 場上亮牌（數字 = 圖案分組），綠框 = 讀到的格子牌。 */
    private fun saveDebug(rgba: Mat, tiles: List<DetectedTile>, types: IntArray, slotTiles: List<Pair<Int, DetectedTile>>) {
        try {
            val img = rgba.clone()
            val red = Scalar(255.0, 0.0, 0.0, 255.0)
            val green = Scalar(0.0, 200.0, 0.0, 255.0)
            for (i in tiles.indices) {
                val t = tiles[i]
                Imgproc.rectangle(img, Point(t.x.toDouble(), t.y.toDouble()),
                    Point((t.x + t.w).toDouble(), (t.y + t.h).toDouble()), red, 5)
                if (i < types.size) {
                    Imgproc.putText(img, types[i].toString(), Point(t.x + 10.0, t.y + 50.0),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, red, 4)
                }
            }
            for ((idx, t) in slotTiles) {
                Imgproc.rectangle(img, Point(t.x.toDouble(), t.y.toDouble()),
                    Point((t.x + t.w).toDouble(), (t.y + t.h).toDouble()), green, 5)
                Imgproc.putText(img, "s$idx", Point(t.x + 10.0, t.y + 50.0),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 1.4, green, 4)
            }
            Imgproc.putText(img, "slot=$slot mode=$slotMode tileW=${TileVision.lastTileWidth}", Point(20.0, 380.0),
                Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, red, 3)
            val small = Mat()
            Imgproc.resize(img, small, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
            val out = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(small, out)
            FileOutputStream(File(debugDir, "last.jpg")).use { out.compress(Bitmap.CompressFormat.JPEG, 75, it) }
            out.recycle()
            small.release()
            img.release()
        } catch (e: Exception) {
            Log.w(TAG, "saveDebug failed", e)
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            if (logFile.length() > 300_000) logFile.delete()
            logFile.appendText("${System.currentTimeMillis() % 1_000_000} $msg\n")
        } catch (e: Exception) {
            // 寫不進去就算了
        }
    }

    companion object {
        private const val TAG = "BotLoop"
        private const val MAX_TAPS = 3             // 一次規劃幾張
        private const val TAP_MS = 150L            // 單次點擊時間
        private const val RETRY_TAP_MS = 230L  // 重試時按更久
        private const val TAP_GAP_MS = 60L         // 確認點掉後，等多久再點下一張
        private const val VERIFY_MS = 500L         // 等多久確認牌被點掉
        private const val CHANGE_THRESHOLD = 12.0  // 前後畫面平均色差（0~255）超過就算「牌不見了」
        private const val SETTLE_POLL_MS = 70L     // 等畫面穩定的檢查間隔
        private const val MAX_SETTLE = 12          // 最多等 12 次（約 1.4 秒），再久就直接決策
    }
}
