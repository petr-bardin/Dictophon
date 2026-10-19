package ru.example.meetingrecorder

/** Monotonic recording time; paused intervals never enter the saved duration. */
internal class RecordingClock(private val now: () -> Long) {
    private var accumulated = 0L
    private var runningSince: Long? = null

    val elapsed: Long get() = accumulated + (runningSince?.let { now() - it } ?: 0L)

    fun start() { accumulated = 0L; runningSince = now() }
    fun pause() { accumulated = elapsed; runningSince = null }
    fun resume() { if (runningSince == null) runningSince = now() }
    fun reset() { accumulated = 0L; runningSince = null }
}
