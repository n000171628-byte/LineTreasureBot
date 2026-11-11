package com.example.linebot.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolverTest {
    private val solver = Solver()

    @Test
    fun completesTripleWhenTwoAlreadyInSlot() {
        val tiles = listOf(Tile(1, 0), Tile(2, 1))
        assertEquals(1, solver.choose(tiles, listOf(0, 0))?.id)
    }

    @Test
    fun prefersMatchingSlotPairOverNewType() {
        val tiles = listOf(Tile(1, 5), Tile(2, 7), Tile(3, 7), Tile(4, 7))
        assertEquals(1, solver.choose(tiles, listOf(5, 5))?.id)
    }

    @Test
    fun returnsNullWhenEveryMoveLoses() {
        val tiles = listOf(Tile(1, 1), Tile(2, 2), Tile(3, 3))
        assertNull(solver.choose(tiles, listOf(0, 0, 4, 4, 5, 5)))
    }

    @Test
    fun usesUnlockHintAsTieBreaker() {
        val tiles = listOf(Tile(1, 0, unlock = 0), Tile(2, 0, unlock = 3), Tile(3, 0, unlock = 1))
        assertEquals(2, solver.choose(tiles, listOf(0, 0))?.id)
    }

    @Test
    fun clearsBoardWhenAllTilesVisible() {
        // 6 種圖案各 3 張，全部可見：應該能完整消完
        val tiles = ArrayList<Tile>()
        var id = 0
        for (type in 0 until 6) repeat(3) { tiles.add(Tile(id++, type)) }
        tiles.shuffle(java.util.Random(1))
        val slot = ArrayList<Int>()
        var guard = 0
        while (tiles.isNotEmpty() && guard++ < 100) {
            val pick = solver.choose(tiles, slot) ?: break
            tiles.remove(pick)
            slot.add(pick.type)
            if (slot.count { it == pick.type } == 3) slot.removeAll { it == pick.type }
            assertTrue("格子不可滿", slot.size < 7)
        }
        assertTrue(tiles.isEmpty())
    }

    @Test
    fun planClicksThreeMatchingTilesInOneGo() {
        val tiles = listOf(Tile(1, 0), Tile(2, 0), Tile(3, 0), Tile(4, 1))
        val plan = solver.plan(tiles, emptyList(), 3)
        assertEquals(listOf(0, 0, 0), plan.map { it.type })
    }

    @Test
    fun planIsEmptyWhenNoSafeMove() {
        val tiles = listOf(Tile(1, 1), Tile(2, 2))
        assertTrue(solver.plan(tiles, listOf(0, 0, 4, 4, 5, 5), 3).isEmpty())
    }

    @Test
    fun alwaysCompletesTripleBeforeAnythingElse() {
        // 即使場上有三張同圖案可以另外湊，格子裡已有兩張時也先補第三張
        val tiles = listOf(Tile(1, 3), Tile(2, 3), Tile(3, 3), Tile(4, 8, unlock = 2))
        assertEquals(8, solver.choose(tiles, listOf(8, 8))?.type)
    }

    @Test
    fun fewestTapsTripleComesFirst() {
        // 格子有 1 張 A；場上 A 有 2 張（點 2 下）、B 有 3 張（點 3 下）→ 先做 A
        val tiles = listOf(Tile(1, 7), Tile(2, 7), Tile(3, 9), Tile(4, 9), Tile(5, 9))
        assertEquals(7, solver.choose(tiles, listOf(7))?.type)
    }

    @Test
    fun threeVisibleSameTypeAreTakenEvenWithEmptySlot() {
        val tiles = listOf(Tile(1, 4), Tile(2, 4), Tile(3, 4), Tile(4, 5), Tile(5, 6))
        assertEquals(4, solver.choose(tiles, emptyList())?.type)
    }

    @Test
    fun whenNoTripleIsPossiblePrefersTypeWithMostCopies() {
        // 沒有任何圖案湊得滿三張：選場上 + 格子有兩張的 9，而不是單張的 1、2
        val tiles = listOf(Tile(1, 1, unlock = 2), Tile(2, 2, unlock = 2), Tile(3, 9), Tile(4, 9))
        assertEquals(9, solver.choose(tiles, emptyList())?.type)
    }

    @Test
    fun anyTypeWithThreeCopiesGetsCompletedFirst() {
        // 不論圖案編號是多少、場上其他牌怎麼擺，只要有 3 張相同的，plan 的前 3 下就是那 3 張
        val rnd = java.util.Random(7)
        repeat(200) {
            val target = rnd.nextInt(12)
            val tiles = ArrayList<Tile>()
            var id = 0
            repeat(3) { tiles.add(Tile(id++, target, rnd.nextInt(3))) }
            // 其他圖案各放 1~2 張（湊不成三張），數量與順序隨機
            for (t in 0 until 12) if (t != target) repeat(rnd.nextInt(3)) { tiles.add(Tile(id++, t, rnd.nextInt(3))) }
            tiles.shuffle(rnd)
            val plan = solver.plan(tiles, emptyList(), 3)
            assertEquals(listOf(target, target, target), plan.map { it.type })
        }
    }

    @Test
    fun planStopsAfterOneTapWhenNotCompletingATriple() {
        // 場上湊不出三張：只點一張，等畫面更新（可能露出新牌）後再決定
        val tiles = listOf(Tile(1, 6), Tile(2, 6), Tile(3, 11), Tile(4, 11), Tile(5, 3))
        assertEquals(1, solver.plan(tiles, listOf(8, 8, 9), 3).size)
    }

    @Test
    fun planKeepsTappingWhileCompletingATriple() {
        val tiles = listOf(Tile(1, 4), Tile(2, 4), Tile(3, 9))
        assertEquals(listOf(4, 4), solver.plan(tiles, listOf(4), 3).map { it.type })
    }

    @Test
    fun waitingPairMeansUncoverTilesFirst() {
        // 格子有一對 8，場上完全沒有 8 → 第三張在下面，優先點能解鎖最多的牌，而不是點成對的 11
        val tiles = listOf(Tile(1, 11, unlock = 0), Tile(2, 11, unlock = 0), Tile(3, 6, unlock = 2), Tile(4, 3, unlock = 0))
        assertEquals(3, solver.choose(tiles, listOf(8, 8, 9))?.id)
    }
}
