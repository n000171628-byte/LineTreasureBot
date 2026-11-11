package com.example.linebot.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 為「同一關內長得一樣的牌」編號。不需要事先準備圖案樣板：
 * 新牌跟已見過的代表圖比對，夠像就是同類，否則開新編號。
 */
class TypeRegistry(private val threshold: Double = 0.85) {
    private val reps = ArrayList<DetectedTile>()

    val size: Int get() = reps.size

    fun typeOf(t: DetectedTile): Int {
        var best = -1
        var bestScore = -1.0
        for (i in reps.indices) {
            val sc = similarity(t, reps[i])
            if (sc > bestScore) {
                bestScore = sc
                best = i
            }
        }
        if (best >= 0 && bestScore >= threshold) return best
        reps.add(DetectedTile(t.x, t.y, t.w, t.h, t.core.clone(), t.search.clone()))
        return reps.size - 1
    }

    /**
     * 格子裡的牌是已經在場上見過的圖案，所以只跟已知類別比、不新增類別。
     * 門檻放寬（格子內的牌大小可能略有不同）；找不到就回傳 null，並把最佳分數寫進 [lastScore]。
     */
    var lastScore: Double = -1.0
        private set

    fun matchKnown(t: DetectedTile, minScore: Double = 0.75): Int? {
        var best = -1
        var bestScore = -1.0
        for (i in reps.indices) {
            val sc = similarity(t, reps[i])
            if (sc > bestScore) {
                bestScore = sc
                best = i
            }
        }
        lastScore = bestScore
        return if (best >= 0 && bestScore >= minScore) best else null
    }

    fun clear() {
        reps.forEach { it.release() }
        reps.clear()
    }

    private fun similarity(a: DetectedTile, b: DetectedTile): Double =
        Math.max(bestMatch(b.search, a.core), bestMatch(a.search, b.core))

    private fun bestMatch(search: Mat, templ: Mat): Double {
        val res = Mat()
        try {
            Imgproc.matchTemplate(search, templ, res, Imgproc.TM_CCOEFF_NORMED)
            val v = Core.minMaxLoc(res).maxVal
            return if (v.isNaN()) -1.0 else v
        } finally {
            res.release()
        }
    }
}
