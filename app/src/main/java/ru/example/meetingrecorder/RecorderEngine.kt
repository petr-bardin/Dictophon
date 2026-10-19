package ru.example.meetingrecorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Тонкая обёртка над MediaRecorder: выбирает оптимальный поддерживаемый
 * микрофоном формат (моно/стерео, битрейт), чтобы запись не обрывалась.
 */
class RecorderEngine {

    private var recorder: MediaRecorder? = null
    private var filePath: String? = null

    fun start(context: Context, path: String): Boolean {
        filePath = path
        val candidates = listOf(
            // битрейт, каналы
            96_000 to 1,
            128_000 to 1,
            64_000 to 1,
            128_000 to 2
        )
        for ((bitrate, channels) in candidates) {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(bitrate)
                    setAudioSamplingRate(44_100)
                    setAudioChannels(channels)
                    setOutputFile(path)
                    prepare()
                    start()
                }
                recorder = r
                return true
            } catch (e: Exception) {
                runCatching { r.release() }
                Log.w(TAG, "Не удалось начать запись ($bitrate/$channels): ${e.message}")
            }
        }
        return false
    }

    fun pause(): Boolean = runCatching { recorder?.pause() ?: error("Recorder unavailable") }.isSuccess

    fun resume(): Boolean = runCatching { recorder?.resume() ?: error("Recorder unavailable") }.isSuccess

    /** MediaRecorder's peak amplitude since the last sample, in the range 0..32767. */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** @return true, если файл записан корректно. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        var ok = false
        try {
            r.stop()
            ok = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Ошибка остановки записи", e)
        } finally {
            runCatching { r.release() }
        }
        if (!ok) {
            filePath?.let { runCatching { java.io.File(it).delete() } }
        }
        filePath = null
        return ok
    }

    fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }

    companion object {
        private const val TAG = "RecorderEngine"
    }
}
