package ru.example.meetingrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/**
 * Фоновый сервис записи звука. Показывает постоянное уведомление с таймером,
 * пишет аудио в файл (m4a/AAC) через RecorderEngine и RecordingRepository.
 */
class RecordingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()

    private val engine = RecorderEngine()
    private var recording = false
    private var outputPath: String? = null
    private var startedAtElapsed: Long = 0L
    private var tickerThread: Thread? = null
    private var stoppedByUs = false

    var onTickListener: ((Long) -> Unit)? = null
    var onFinishedListener: (() -> Unit)? = null

    val isRunning: Boolean get() = recording

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!recording) {
                    val title = intent.getStringExtra(EXTRA_TITLE)
                    startRecording(title)
                }
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(purpose: Intent?): IBinder = binder

    override fun onDestroy() {
        // Если сервис убивают не по нашей команде — аккуратно освобождаем ресурсы
        if (recording) {
            engine.release()
            if (!stoppedByUs) {
                outputPath?.let { runCatching { java.io.File(it).delete() } }
            }
            recording = false
            isRecordingNow = false
        }
        super.onDestroy()
    }

    // ---------- Логика записи ----------

    private fun startRecording(title: String?) {
        try {
            val file = RecordingRepository.createFile(this, title)
            outputPath = file.absolutePath

            if (!engine.start(this, file.absolutePath)) {
                throw IllegalStateException("Не удалось запустить MediaRecorder")
            }
            recording = true

            startedAtElapsed = SystemClock.elapsedRealtime()
            isRecordingNow = true
            startForegroundInternal()
            startTicker()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка старта записи", e)
            recording = false
            isRecordingNow = false
            engine.release()
            outputPath?.let { runCatching { java.io.File(it).delete() } }
            outputPath = null
            onFinishedListener?.invoke()
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!recording) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val out = outputPath
        stoppedByUs = true
        recording = false
        val ok = engine.stop()

        isRecordingNow = false
        tickerThread?.interrupt()
        tickerThread = null

        if (ok && out != null) {
            RecordingRepository.registerCompleted(this, out)
        }
        outputPath = null
        onFinishedListener?.invoke()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Вызывается из MainActivity для проверки состояния. */
    fun isRecording(): Boolean = recording

    private fun startTicker() {
        tickerThread = thread(isDaemon = true) {
            while (recording) {
                val elapsed = SystemClock.elapsedRealtime() - startedAtElapsed
                onTickListener?.invoke(elapsed)
                updateNotification(elapsed)
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    // ---------- Уведомление ----------

    private fun startForegroundInternal() {
        val notification = buildNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val minutes = elapsedMs / 60_000
        val seconds = (elapsedMs / 1000) % 60
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(String.format(java.util.Locale.US, "%d:%02d", minutes, seconds))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .build()
    }

    private fun updateNotification(elapsedMs: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(elapsedMs))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "ru.example.meetingrecorder.START"
        const val ACTION_STOP = "ru.example.meetingrecorder.STOP"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"

        /** Простой флаг состояния, чтобы UI знал о записи до привязки к сервису. */
        @Volatile
        var isRecordingNow: Boolean = false
            private set
    }
}
