package io.github.abhishekkaisar.glassos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScenePlaylistTest {
    private val clips = (1..8).map { "%02d-clip.mp4".format(it) }

    private fun shuffled(seed: Int, ids: List<String> = clips) = ScenePlaylist(Random(seed)).apply {
        setLibrary(ids); setShuffle(true); setEligible(ids)
    }

    @Test fun shuffleCyclePlaysEveryClipOnceBeforeAnyRepeat() {
        for (seed in 0 until 50) {
            val p = shuffled(seed)
            val first = (1..clips.size).map { p.next()!! }
            assertEquals("seed $seed", clips.toSet(), first.toSet())
            val second = (1..clips.size).map { p.next()!! }
            assertEquals(clips.toSet(), second.toSet())
            assertNotEquals("no back-to-back repeat across the cycle boundary (seed $seed)", first.last(), second.first())
        }
    }

    @Test fun boundaryNeverRepeatsEvenWithTwoClips() {
        for (seed in 0 until 50) {
            val p = shuffled(seed, clips.take(2))
            var last = p.next()
            repeat(20) { val n = p.next(); assertNotEquals(last, n); last = n }
        }
    }

    @Test fun singleClipRepeats() {
        val p = shuffled(1, clips.take(1))
        assertEquals(clips[0], p.next()); assertEquals(clips[0], p.next())
    }

    @Test fun hiddenClipIsNeverChosenAgain() {
        val p = shuffled(3)
        val a = p.next()!!
        val hiddenOne = p.remaining.first()
        p.setEligible(clips - hiddenOne)
        val rest = (1..30).map { p.next()!! }
        assertFalse(hiddenOne in rest)
        assertTrue(a in rest)
    }

    @Test fun hidingCurrentReportsIneligible() {
        val p = shuffled(4)
        val cur = p.next()!!
        assertFalse(p.setEligible(clips - cur))
        assertNotEquals(cur, p.next())
    }

    @Test fun unhiddenMidCycleJoinsTheCurrentCycle() {
        val p = shuffled(5, clips.dropLast(1))
        p.next(); p.next()
        p.setEligible(clips)
        // The newly eligible clip comes before the cycle ends (within the remaining picks of this cycle).
        val rest = (1..p.remaining.size).map { p.next()!! }
        assertTrue(clips.last() in rest)
        assertEquals(rest.size, rest.toSet().size)
    }

    @Test fun reEnablingAClipAlreadyPlayedThisCycleDoesNotReplayItEarly() {
        val p = shuffled(6)
        val a = p.next()!!
        p.next()
        p.setEligible(clips - a)
        p.setEligible(clips)
        val rest = (1..p.remaining.size).map { p.next()!! }
        assertFalse(a in rest)
    }

    @Test fun selectRemovesTheChoiceFromTheRestOfTheCycle() {
        val p = shuffled(7)
        p.next()
        val pick = p.remaining.last()
        p.select(pick)
        assertEquals(pick, p.current)
        val rest = (1..p.remaining.size).map { p.next()!! }
        assertFalse(pick in rest)
    }

    @Test fun stateRoundTripKeepsTheRestOfTheCycle() {
        val p = shuffled(8)
        repeat(3) { p.next() }
        val saved = p.save()
        val q = ScenePlaylist(Random(99)).apply { restore(saved); setLibrary(clips); setShuffle(true); setEligible(clips) }
        assertEquals(p.current, q.current)
        assertEquals(p.remaining, q.remaining)
        val played = clips.toSet() - p.remaining.toSet()
        val rest = (1..q.remaining.size).map { q.next()!! }
        assertTrue(rest.none { it in played })
    }

    @Test fun malformedStateIsIgnored() {
        val p = ScenePlaylist(Random(1))
        p.restore("garbage"); p.restore("v1\n1\n../x\n\n"); p.restore(null)
        assertNull(p.current)
    }

    @Test fun orderModeFollowsLibraryAndSkipsIneligible() {
        val p = ScenePlaylist(Random(1)).apply { setLibrary(clips); setEligible(clips) }
        assertEquals(clips[0], p.next()); assertEquals(clips[1], p.next())
        p.setEligible(clips.filterIndexed { i, _ -> i != 2 })
        assertEquals(clips[3], p.next())
        p.select(clips[7])
        assertEquals(clips[0], p.next())
        assertEquals(clips[7], p.previous())
    }

    @Test fun orderModeAfterCurrentBecomesIneligibleContinuesFromItsPlace() {
        val p = ScenePlaylist(Random(1)).apply { setLibrary(clips); setEligible(clips) }
        p.select(clips[4])
        p.setEligible(clips - clips[4])
        assertEquals(clips[5], p.next())
    }

    @Test fun nothingEligible() {
        val p = shuffled(1)
        p.setEligible(emptyList())
        assertNull(p.next())
        assertNull(p.current)
    }

    /** Previous at the start of a shuffled session has nowhere to go: nothing changes (the player keeps playing). */
    @Test fun previousWithNoHistoryChangesNothing() {
        val p = shuffled(10)
        val a = p.next()!!
        val rest = p.remaining
        assertNull(p.previous())
        assertEquals(a, p.current)
        assertEquals(rest, p.remaining)
        assertEquals(rest.first(), p.next())
    }

    @Test fun previousInShuffleReturnsAndNextComesBack() {
        val p = shuffled(9)
        val a = p.next()!!
        val b = p.next()!!
        assertEquals(a, p.previous())
        assertEquals(b, p.next())
    }
}
