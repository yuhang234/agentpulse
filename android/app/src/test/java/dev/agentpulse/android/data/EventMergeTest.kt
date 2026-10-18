package dev.agentpulse.android.data

import dev.agentpulse.android.data.model.EventKind
import dev.agentpulse.android.data.model.EventSource
import dev.agentpulse.android.data.model.PulseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event merging by `seq`, per design report 2.2.C / 2.4.2: strictly increasing global sequence,
 * idempotent dedupe, ascending order, bounded ring buffer.
 */
class EventMergeTest {

    private fun event(seq: Long, eventId: String = "evt_$seq", kind: EventKind = EventKind.PaneOutput) =
        PulseEvent(
            eventId = eventId,
            seq = seq,
            source = EventSource.Herdr,
            kind = kind,
        )

    @Test
    fun `merge returns events in ascending seq order regardless of insertion`() {
        val timeline = EventTimeline()
        timeline.merge(listOf(event(3), event(1), event(2)))

        assertEquals(listOf(1L, 2L, 3L), timeline.snapshot().map { it.seq })
        assertEquals(3L, timeline.maxSeq)
    }

    @Test
    fun `duplicate seq is dropped`() {
        val timeline = EventTimeline()
        val first = timeline.merge(event(1))
        val duplicate = timeline.merge(event(1, eventId = "evt_other"))

        assertEquals(1, timeline.size)
        assertTrue(first)
        assertFalse(duplicate)
    }

    @Test
    fun `same event id arriving twice is idempotent`() {
        val timeline = EventTimeline()
        val e = event(7, eventId = "evt_same")
        assertTrue(timeline.merge(e))
        assertFalse(timeline.merge(e))

        assertEquals(1, timeline.size)
    }

    @Test
    fun `interleaved rest and ws delivery collapses to one entry`() {
        val timeline = EventTimeline()
        timeline.merge(listOf(event(1), event(3)))
        // WS replays seq 2 that REST already had, plus a new seq 4.
        val added = timeline.merge(listOf(event(2), event(3), event(4)))

        assertEquals(listOf(1L, 2L, 3L, 4L), timeline.snapshot().map { it.seq })
        // seq 3 was already present, so only 2 and 4 are new.
        assertEquals(listOf(2L, 4L), added.map { it.seq })
    }

    @Test
    fun `max seq advances and is used as resume cursor`() {
        val timeline = EventTimeline()
        assertEquals(0L, timeline.maxSeq)
        timeline.merge(event(42))
        assertEquals(42L, timeline.maxSeq)
    }

    @Test
    fun `capacity evicts oldest and counts evictions`() {
        val timeline = EventTimeline(capacity = 3)
        timeline.merge(listOf(event(1), event(2), event(3), event(4), event(5)))

        assertEquals(3, timeline.size)
        assertEquals(listOf(3L, 4L, 5L), timeline.snapshot().map { it.seq })
        assertEquals(2, timeline.evictedCount)
    }

    @Test
    fun `local synthesized events without seq stay ordered after real ones`() {
        val timeline = EventTimeline()
        timeline.merge(event(10))
        val synthetic = PulseEvent(eventId = "local:1", seq = 0L, source = EventSource.Service, kind = EventKind.Unknown)
        timeline.merge(synthetic)

        assertEquals(2, timeline.size)
        assertEquals(0L, timeline.snapshot()[1].seq)
        // dedupe key falls back to eventId for seq-less events.
        assertEquals("local:1", synthetic.dedupeKey)
    }

    @Test
    fun `clear resets cursor and buffer`() {
        val timeline = EventTimeline()
        timeline.merge(listOf(event(1), event(2)))
        timeline.clear()

        assertTrue(timeline.isEmpty)
        assertEquals(0L, timeline.maxSeq)
        assertEquals(0, timeline.evictedCount)
    }
}
