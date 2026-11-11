package com.example.linebot.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 一張亮牌：螢幕座標 + 供比對圖案用的區塊（RGB，已縮小）。unlock 是點掉後可能露出更多牌的粗略分數 0..2。 */
class DetectedTile(
    val x: Int, val y: Int, val w: Int, val h: Int,
    val core: Mat, val search: Mat,
    val unlock: Int = 0
) {
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2
    fun release() {
        core.release()
        search.release()
    }
}

/**
 * 找出畫面上米白色的亮牌（可點擊）以及底下格子裡的牌。
 *
 * 牌的大小會隨關卡改變（牌多時小、牌少時大，例如同樣 1189 px 寬，有的關卡牌寬 128、有的 146），
 * 所以每個畫面都從偵測到的亮牌寬度中位數重新量測，不寫死。
 * 底下格子是固定的介面元件，位置以畫面寬度等比縮放（1189 px 寬的量測值），高度方向會自動搜尋。
 * 對應 tools/prototype/tile_prototype.py。
 */
object TileVision {
    private const val REF_W = 1189.0
    private const val ASPECT = 0.96        // 牌高 / 牌寬
    private const val PITCH = 1.075        // 相鄰牌中心距 / 牌寬
    // 垂直方向用「螢幕高度比例」定位（不同手機長寬比不同，用寬度縮放會讓格子列蓋到最下排牌）
    private const val TOP_FRAC = 0.128     // 上方狀態列
    private const val BOTTOM_FRAC = 0.776  // 下方格子、道具、廣告

    // 底下 7 個格子（1189 px 寬量測）：第 0 格中心 x、格距、格內牌的寬高
    private const val SLOT_CENTER0 = 213.2
    private const val SLOT_PITCH = 126.9
    private const val SLOT_TILE_W = 117.0
    private const val SLOT_TILE_H = 113.0
    private const val SLOT_TOP_FRAC = 0.778
    private const val SLOT_BOTTOM_FRAC = 0.840

    // 比對用的縮小尺寸
    private const val CANON_W = 64
    private const val CANON_H = 62

    private val CREAM_LO = Scalar(18.0, 25.0, 205.0)
    private val CREAM_HI = Scalar(40.0, 120.0, 255.0)
    private val DARK_LO = Scalar(15.0, 20.0, 95.0)
    private val DARK_HI = Scalar(45.0, 130.0, 200.0)

    /** 最近一次量到的牌寬（像素），給除錯用。 */
    @Volatile
    var lastTileWidth: Int = 0
        private set

    fun detect(rgba: Mat): List<DetectedTile> {
        val s = rgba.cols() / REF_W
        val top = (TOP_FRAC * rgba.rows()).toInt().coerceIn(0, rgba.rows() - 1)
        val bottom = (BOTTOM_FRAC * rgba.rows()).toInt().coerceIn(top + 1, rgba.rows())
        val roi = rgba.submat(top, bottom, 0, rgba.cols())
        val rgb = Mat()
        val hsv = Mat()
        val mask = Mat()
        val dark = Mat()
        val labels = Mat()
        val stats = Mat()
        val cent = Mat()
        try {
            Imgproc.cvtColor(roi, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv, CREAM_LO, CREAM_HI, mask)
            Core.inRange(hsv, DARK_LO, DARK_HI, dark)
            // 核心不能太大，否則相鄰牌（間隙約 8~11 px）會被黏成一塊
            closeMask(mask, (5 * s).toInt())

            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, cent)
            class Blob(val x: Int, val y: Int, val w: Int, val h: Int)
            val blobs = ArrayList<Blob>()
            val st = IntArray(5)
            for (i in 1 until n) {
                stats.get(i, 0, st)
                val w = st[Imgproc.CC_STAT_WIDTH]
                val h = st[Imgproc.CC_STAT_HEIGHT]
                if (st[Imgproc.CC_STAT_AREA] < 2500 * s * s) continue
                blobs.add(Blob(st[Imgproc.CC_STAT_LEFT], st[Imgproc.CC_STAT_TOP], w, h))
            }

            // 牌寬 = 近似正方形的亮塊寬度中位數
            val widths = blobs.filter {
                val r = it.w.toDouble() / it.h
                r in 0.95..1.15 && it.w >= 80 * s && it.w <= 250 * s
            }.map { it.w }.sorted()
            if (widths.isEmpty()) {
                lastTileWidth = 0
                return emptyList()
            }
            val tw = widths[widths.size / 2].toDouble()
            val th = tw * ASPECT
            val pitch = tw * PITCH
            lastTileWidth = tw.toInt()

            val out = ArrayList<DetectedTile>()
            for (b in blobs) {
                // 少數仍黏在一起的牌，依牌距切開
                val cols = if (b.w > tw * 1.3) Math.round((b.w - tw) / pitch).toInt() + 1 else 1
                val rows = if (b.h > th * 1.3) Math.round((b.h - th) / pitch).toInt() + 1 else 1
                for (r in 0 until rows.coerceIn(1, 8)) {
                    for (c in 0 until cols.coerceIn(1, 8)) {
                        val tx = (b.x + if (cols > 1) c * (b.w - tw) / (cols - 1) else 0.0).toInt()
                        val ty = (b.y + if (rows > 1) r * (b.h - th) / (rows - 1) else 0.0).toInt()
                        val hint = unlockHint(dark, tx, ty, tw, th)
                        extract(rgb, tx, ty, tw.toInt(), th.toInt(), top, hint)?.let { out.add(it) }
                    }
                }
            }
            return out
        } finally {
            roi.release(); rgb.release(); hsv.release(); mask.release(); dark.release()
            labels.release(); stats.release(); cent.release()
        }
    }

    /**
     * 讀底下 7 個格子，回傳 (格子編號 0..6, 牌)。
     * 先在 7 格的欄位範圍內往下掃，找出米白色最多的一段高度當作格子列，再逐格判斷有沒有牌。
     */
    fun detectSlot(rgba: Mat): List<Pair<Int, DetectedTile>> {
        val s = rgba.cols() / REF_W
        val top = (SLOT_TOP_FRAC * rgba.rows()).toInt().coerceIn(0, rgba.rows() - 1)
        val bottom = (SLOT_BOTTOM_FRAC * rgba.rows()).toInt().coerceIn(top + 1, rgba.rows())
        if (bottom - top < 60) return emptyList()
        val roi = rgba.submat(top, bottom, 0, rgba.cols())
        val rgb = Mat()
        val hsv = Mat()
        val mask = Mat()
        val rowSum = Mat()
        try {
            Imgproc.cvtColor(roi, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv, CREAM_LO, CREAM_HI, mask)

            val cx0 = SLOT_CENTER0 * s
            val cx6 = (SLOT_CENTER0 + 6 * SLOT_PITCH) * s
            val xa = (cx0 - SLOT_TILE_W / 2 * s).toInt().coerceIn(0, mask.cols() - 1)
            val xb = (cx6 + SLOT_TILE_W / 2 * s).toInt().coerceIn(xa + 1, mask.cols())
            val band = mask.submat(0, mask.rows(), xa, xb)
            Core.reduce(band, rowSum, 1, Core.REDUCE_SUM, org.opencv.core.CvType.CV_32S)
            band.release()
            val rows = rowSum.rows()
            val prof = IntArray(rows)
            rowSum.get(0, 0, prof)

            val h = (SLOT_TILE_H * s).toInt().coerceIn(10, rows)
            var cur = 0L
            for (i in 0 until h) cur += prof[i]
            var best = cur
            var bestY = 0
            for (y in 1..(rows - h)) {
                cur += prof[y + h - 1] - prof[y - 1]
                if (cur > best) { best = cur; bestY = y }
            }
            val w = (SLOT_TILE_W * s).toInt()
            val out = ArrayList<Pair<Int, DetectedTile>>()
            for (i in 0..6) {
                val cx = (SLOT_CENTER0 + i * SLOT_PITCH) * s
                val x = (cx - w / 2.0).toInt()
                if (x < 0 || x + w > mask.cols() || bestY + h > mask.rows()) continue
                val cell = mask.submat(bestY, bestY + h, x, x + w)
                val frac = Core.countNonZero(cell).toDouble() / (w * h)
                cell.release()
                if (frac < 0.15) continue // 空格
                extract(rgb, x, bestY, w, h, top, 0)?.let { out.add(Pair(i, it)) }
            }
            return out
        } finally {
            roi.release(); rgb.release(); hsv.release(); mask.release(); rowSum.release()
        }
    }

    private fun closeMask(mask: Mat, kRaw: Int) {
        var k = kRaw.coerceAtLeast(3)
        if (k % 2 == 0) k += 1
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()
    }

    /** 牌周圍深色（被壓住的）牌越多，點掉後越可能露出新牌。只拿來當同分時的參考。 */
    private fun unlockHint(dark: Mat, x: Int, y: Int, tw: Double, th: Double): Int {
        val mx = (0.25 * tw).toInt()
        val my = (0.25 * th).toInt()
        val x0 = (x - mx).coerceAtLeast(0)
        val y0 = (y - my).coerceAtLeast(0)
        val x1 = (x + tw.toInt() + mx).coerceAtMost(dark.cols())
        val y1 = (y + th.toInt() + my).coerceAtMost(dark.rows())
        if (x1 <= x0 || y1 <= y0) return 0
        val sub = dark.submat(y0, y1, x0, x1)
        val frac = Core.countNonZero(sub).toDouble() / ((x1 - x0) * (y1 - y0))
        sub.release()
        return if (frac >= 0.20) 2 else if (frac >= 0.14) 1 else 0
    }

    private fun extract(rgb: Mat, x: Int, y: Int, tw: Int, th: Int, yOffset: Int, unlock: Int): DetectedTile? {
        val rx = x.coerceIn(0, rgb.cols() - 1)
        val ry = y.coerceIn(0, rgb.rows() - 1)
        val w = Math.min(tw, rgb.cols() - rx)
        val h = Math.min(th, rgb.rows() - ry)
        if (w < 20 || h < 20) return null
        val src = rgb.submat(Rect(rx, ry, w, h))
        val big = Mat()
        Imgproc.resize(src, big, Size(CANON_W.toDouble(), CANON_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        src.release()
        val core = big.submat(14, 51, 14, 50).clone()
        val search = big.submat(7, 58, 7, 57).clone()
        big.release()
        return DetectedTile(rx, ry + yOffset, w, h, core, search, unlock)
    }
}
