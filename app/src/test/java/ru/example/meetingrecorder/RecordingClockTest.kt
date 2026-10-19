package ru.example.meetingrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingClockTest {
    @Test fun pausedTimeNeverEntersRecordingDuration() {
        var now = 1_000L
        val clock = RecordingClock { now }
        clock.start(); now += 2_500; clock.pause()
        assertEquals(2_500L, clock.elapsed)
        now += 60_000
        assertEquals(2_500L, clock.elapsed)
        clock.resume(); now += 1_750
        assertEquals(4_250L, clock.elapsed)
    }
    @Test fun repeatedPausesAndResumeAreIdempotent() {
        var now = 0L
        val clock = RecordingClock { now }
        clock.start(); now = 500; clock.pause(); now = 900; clock.pause()
        assertEquals(500L, clock.elapsed)
        clock.resume(); now = 1_000; clock.resume(); now = 1_200
        assertEquals(800L, clock.elapsed)
    }
    @Test fun startingAnotherRecordingClearsPriorElapsedTime() {
        var now = 100L
        val clock = RecordingClock { now }
        clock.start(); now = 9_000; clock.pause(); clock.reset()
        assertEquals(0L, clock.elapsed)
        now = 11_000; clock.start(); now = 11_400
        assertEquals(400L, clock.elapsed)
    }
}
