package ru.example.meetingrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/** Owns the microphone independently of the activity. All UI callbacks run on the main thread. */
class RecordingService : Service() {
    enum class State { IDLE, RECORDING, PAUSED, SAVING }
    inner class LocalBinder : Binder() { fun getService() = this@RecordingService }
    private val binder = LocalBinder()
    private val engine = RecorderEngine()
    private val clock = RecordingClock(SystemClock::elapsedRealtime)
    private val handler = Handler(Looper.getMainLooper())
    private val storage = Executors.newSingleThreadExecutor()
    private var outputPath: String? = null
    private var notificationAt = 0L
    private val markers = mutableListOf<Long>()

    var state: State = State.IDLE
        private set
    val elapsedMs: Long get() = clock.elapsed
    val markerCount: Int get() = markers.size
    var onTickListener: ((Long, Int) -> Unit)? = null
    var onStateListener: ((State) -> Unit)? = null
    var onFinishedListener: ((Uri?, String?) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (state != State.RECORDING && state != State.PAUSED) return
            onTickListener?.invoke(elapsedMs, if (state == State.RECORDING) engine.amplitude() else 0)
            val now = SystemClock.elapsedRealtime()
            if (now - notificationAt >= 1_000) { updateNotification(); notificationAt = now }
            handler.postDelayed(this, 80)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (state == State.IDLE) startRecording()
            ACTION_TOGGLE_PAUSE -> if (state == State.RECORDING) pauseRecording() else if (state == State.PAUSED) resumeRecording()
            ACTION_STOP -> finishRecording(null)
        }
        return START_NOT_STICKY
    }

    private fun changeState(next: State) { state = next; onStateListener?.invoke(next) }

    private fun startRecording() {
        try {
            // Enter foreground immediately; do not wait for recorder preparation or filesystem work.
            startForegroundInternal()
            outputPath = RecordingRepository.createFile(this, null).absolutePath
            check(engine.start(this, outputPath!!)) { getString(R.string.record_start_failed) }
            markers.clear(); clock.start(); notificationAt = 0
            changeState(State.RECORDING)
            handler.post(ticker)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start recording", e)
            engine.release(); outputPath?.let { java.io.File(it).delete() }; outputPath = null
            clock.reset(); changeState(State.IDLE)
            onFinishedListener?.invoke(null, getString(R.string.record_start_failed))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    fun pauseRecording(): Boolean {
        if (state != State.RECORDING) return false
        if (!engine.pause()) { onFinishedListener?.invoke(null, getString(R.string.pause_failed)); return false }
        clock.pause(); changeState(State.PAUSED); updateNotification()
        return true
    }

    fun resumeRecording(): Boolean {
        if (state != State.PAUSED) return false
        if (!engine.resume()) { onFinishedListener?.invoke(null, getString(R.string.resume_failed)); return false }
        clock.resume(); changeState(State.RECORDING); updateNotification()
        return true
    }

    fun addMarker(): Long? {
        if (state != State.RECORDING && state != State.PAUSED) return null
        return elapsedMs.also { markers += it }
    }

    fun finishRecording(title: String?) {
        if (state != State.RECORDING && state != State.PAUSED) return
        clock.pause(); handler.removeCallbacks(ticker)
        val path = outputPath; outputPath = null
        val savedMarkers = markers.toList()
        val stopped = engine.stop()
        changeState(State.SAVING); updateNotification()
        storage.execute {
            var uri: Uri? = null
            var error: String? = null
            try {
                check(stopped && path != null) { getString(R.string.record_save_failed) }
                uri = RecordingRepository.registerCompleted(this, path, title)
                RecordingRepository.saveMarkers(this, uri, savedMarkers)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save recording", e)
                error = getString(R.string.record_save_failed)
            }
            handler.post {
                clock.reset(); markers.clear(); changeState(State.IDLE)
                onFinishedListener?.invoke(uri, error)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }

    private fun startForegroundInternal() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (state) { State.PAUSED -> R.string.status_paused; State.SAVING -> R.string.status_saving; else -> R.string.notif_title }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(title)).setContentText(RecordingRepository.formatDuration(elapsedMs / 1000))
            .setSmallIcon(R.drawable.ic_mic).setContentIntent(open).setOngoing(true).setSilent(true)
            .setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (state != State.SAVING) {
            builder.addAction(0, getString(if (state == State.PAUSED) R.string.btn_resume else R.string.btn_pause), pause)
            builder.addAction(0, getString(R.string.notif_stop), stop)
        }
        return builder.build()
    }

    private fun updateNotification() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        onTickListener = null; onStateListener = null; onFinishedListener = null
        // Preserve a valid interrupted recording in app storage whenever the recorder can finalize it.
        if (state == State.RECORDING || state == State.PAUSED) {
            val path = outputPath
            if (engine.stop() && path != null) storage.execute { runCatching { RecordingRepository.registerCompleted(this, path, null) } }
        } else engine.release()
        storage.shutdown(); super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.example.meetingrecorder.START"
        const val ACTION_STOP = "ru.example.meetingrecorder.STOP"
        const val ACTION_TOGGLE_PAUSE = "ru.example.meetingrecorder.PAUSE"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"
    }
}
