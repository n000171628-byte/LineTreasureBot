package com.example.linebot.solver

/** 一張目前可點擊的牌。type 是同關內的圖案分組編號，unlock 是點掉後預估可解鎖的牌數。 */
data class Tile(val id: Int, val type: Int, val unlock: Int = 0)

/**
 * 只依「目前可點的亮牌 + 底下格子內容」決定下一步。
 *
 * 1. 能湊成三張就先湊，點的張數越少越優先（格子 2 張 + 場上 1 張 > 格子 1 + 場上 2 > 場上 3 張）。
 * 2. 否則往前預測 [depth] 步，只搜尋「格子 + 場上 >= 3 張」、有機會湊成三張的圖案；
 *    其他圖案點了只會佔格子，不值得搜尋。評分 = 消除組數 * 100 - 格子佔用 * 10 - 湊不齊的牌 * 15。
 * 3. 沒有任何圖案湊得成三張時：若格子裡有「等第三張」而場上看不到的一對，優先點能解鎖最多牌的；
 *    否則選「格子 + 場上同圖案張數最多」的牌（格子快滿時回傳 null）。
 */
class Solver(private val capacity: Int = 7, private val depth: Int = 7) {

    fun choose(tiles: List<Tile>, slot: List<Int>): Tile? {
        if (tiles.isEmpty()) return null

        val visCount = HashMap<Int, Int>()
        for (t in tiles) visCount[t.type] = (visCount[t.type] ?: 0) + 1
        val slotCount = HashMap<Int, Int>()
        for (t in slot) slotCount[t] = (slotCount[t] ?: 0) + 1

        // 規則 1（最優先）：能湊成三張就先湊
        completingPick(tiles, slot)?.let { return it }

        val candidates = visCount.keys
            .filter { (slotCount[it] ?: 0) + (visCount[it] ?: 0) >= 3 }
            .sortedWith(compareBy<Int>({ -(slotCount[it] ?: 0) }, { -(visCount[it] ?: 0) }, { it }))
            .take(MAX_TYPES)

        // 規則 3：場上湊不出任何三張
        if (candidates.isEmpty()) {
            if (slot.size + 1 >= capacity) return null
            // 格子裡有「等第三張」的一對，而場上完全看不到那種牌 → 第三張一定被壓在別的牌下面，
            // 優先點能解鎖最多牌的（把它翻出來）；否則優先點同圖案張數最多的（最有機會湊成三張）。
            val waitingPair = slotCount.any { (type, c) -> c >= 2 && (visCount[type] ?: 0) == 0 }
            val cmp = if (waitingPair) {
                compareBy<Tile>(
                    { -it.unlock },
                    { -((slotCount[it.type] ?: 0) + (visCount[it.type] ?: 0)) },
                    { it.id }
                )
            } else {
                compareBy<Tile>(
                    { -((slotCount[it.type] ?: 0) + (visCount[it.type] ?: 0)) },
                    { -it.unlock },
                    { it.id }
                )
            }
            return tiles.sortedWith(cmp).first()
        }

        // 規則 2
        val n = candidates.size
        val v = IntArray(n) { visCount[candidates[it]] ?: 0 }
        val sc = IntArray(n) { slotCount[candidates[it]] ?: 0 }
        val memo = HashMap<Long, Int>()

        var bestScore = Int.MIN_VALUE
        val bestTypes = HashSet<Int>()
        for (i in 0 until n) {
            val c = sc[i] + 1
            val score: Int
            if (c == 3) {
                val old = sc[i]
                sc[i] = 0; v[i]--
                score = CLEAR + search(sc, v, n, slot.size - 2, depth - 1, memo)
                sc[i] = old; v[i]++
            } else {
                if (slot.size + 1 >= capacity) continue // 格子滿且沒消除 = 失敗
                sc[i] = c; v[i]--
                score = search(sc, v, n, slot.size + 1, depth - 1, memo)
                sc[i] = c - 1; v[i]++
            }
            if (score > bestScore) {
                bestScore = score
                bestTypes.clear()
                bestTypes.add(candidates[i])
            } else if (score == bestScore) {
                bestTypes.add(candidates[i])
            }
        }
        if (bestTypes.isEmpty()) return null
        return tiles.filter { it.type in bestTypes }
            .sortedWith(compareBy<Tile>({ -it.unlock }, { it.id }))
            .first()
    }

    /**
     * 規則 1：能湊成三張就先湊，需要點的張數越少越優先。
     *   格子已有 2 張 + 場上有 1 張（點 1 下）> 格子有 1 張 + 場上有 2 張（點 2 下）> 場上有 3 張（點 3 下）
     *   只要過程中格子不會爆（點到最後一張前，格子張數 <= capacity - 1）。找不到回傳 null。
     */
    private fun completingPick(tiles: List<Tile>, slot: List<Int>): Tile? {
        val visCount = HashMap<Int, Int>()
        for (t in tiles) visCount[t.type] = (visCount[t.type] ?: 0) + 1
        val slotCount = HashMap<Int, Int>()
        for (t in slot) slotCount[t] = (slotCount[t] ?: 0) + 1

        var bestType = -1
        var bestNeed = Int.MAX_VALUE
        var bestUnlock = -1
        for ((type, vis) in visCount) {
            val need = 3 - (slotCount[type] ?: 0)
            if (need <= 0 || vis < need) continue
            if (slot.size + need - 1 > capacity - 1) continue
            val unlock = tiles.filter { it.type == type }.maxOf { it.unlock }
            if (need < bestNeed || (need == bestNeed && (unlock > bestUnlock || (unlock == bestUnlock && type < bestType)))) {
                bestType = type; bestNeed = need; bestUnlock = unlock
            }
        }
        if (bestNeed == Int.MAX_VALUE) return null
        return tiles.filter { it.type == bestType }.sortedWith(compareBy<Tile>({ -it.unlock }, { it.id })).first()
    }

    /** 就地修改 sc / v 並在回溯時還原，避免大量配置物件；memo 以壓縮成 Long 的狀態為 key。 */
    private fun search(sc: IntArray, v: IntArray, n: Int, size: Int, d: Int, memo: HashMap<Long, Int>): Int {
        if (d == 0) return evaluate(sc, v, n, size)
        var key = 0L
        for (i in 0 until n) key = (key shl 5) or (sc[i].toLong() shl 3) or Math.min(v[i], 7).toLong()
        key = (key shl 4) or d.toLong()
        memo[key]?.let { return it }

        var best = Int.MIN_VALUE
        for (i in 0 until n) {
            if (v[i] == 0) continue
            val c = sc[i] + 1
            val s: Int
            if (c == 3) {
                val old = sc[i]
                sc[i] = 0; v[i]--
                s = CLEAR + search(sc, v, n, size - 2, d - 1, memo)
                sc[i] = old; v[i]++
            } else if (size + 1 >= capacity) {
                s = -DEAD
            } else {
                sc[i] = c; v[i]--
                s = search(sc, v, n, size + 1, d - 1, memo)
                sc[i] = c - 1; v[i]++
            }
            if (s > best) best = s
        }
        val result = if (best == Int.MIN_VALUE) evaluate(sc, v, n, size) else best
        memo[key] = result
        return result
    }

    private fun evaluate(sc: IntArray, v: IntArray, n: Int, size: Int): Int {
        var pen = 0
        for (i in 0 until n) if (sc[i] > 0 && v[i] < 3 - sc[i]) pen += STRAND * sc[i]
        return -size * SIZE_W - pen
    }

    /**
     * 規劃這一輪要點的牌。
     * - 第一張照 [choose] 選。
     * - 之後只有在「繼續湊同一組三張」（規則 1）時才連點；不是湊三張的步驟只點一張就停，
     *   因為點掉一張牌可能露出新牌、改變最好的下一步，應該重新看過畫面再決定。
     */
    fun plan(tiles: List<Tile>, slot: List<Int>, maxMoves: Int = 3): List<Tile> {
        val remaining = tiles.toMutableList()
        val sim = slot.toMutableList()
        val out = ArrayList<Tile>()
        for (i in 0 until maxMoves) {
            val pick = (if (i == 0) choose(remaining, sim) else completingPick(remaining, sim)) ?: break
            out.add(pick)
            remaining.remove(pick)
            sim.add(pick.type)
            if (sim.count { it == pick.type } == 3) sim.removeAll { it == pick.type }
        }
        return out
    }

    companion object {
        private const val MAX_TYPES = 10 // Long key 最多容納 10 種圖案
        private const val CLEAR = 100
        private const val SIZE_W = 10
        private const val STRAND = 15
        private const val DEAD = 1_000_000
    }
}
