package ru.example.meetingrecorder

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Модель записи совещания. */
data class Recording(
    val id: Long,
    val title: String,
    val dateMillis: Long,
    val durationSec: Long,
    val sizeBytes: Long,
    val uri: Uri
)

/**
 * Хранилище записей.
 * На Android 10+ файлы сохраняются через MediaStore (папка Music/MeetingRecorder),
 * чтобы их могли видеть другие приложения-плееры. На старых версиях — во внутренние файлы.
 */
object RecordingRepository {

    private const val RELATIVE_DIR = "Music/MeetingRecorder"
    private const val LEGACY_DIR_NAME = "recordings"

    fun createFile(context: Context, title: String?): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = title?.replace(Regex("[^A-Za-zА-Яа-я0-9 _-]"), "")?.trim()
        val name = if (safeTitle.isNullOrEmpty()) "Совещание_$stamp" else "${safeTitle}_$stamp"
        val fileName = "$name.m4a"

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // MediaRecorder требует файловый путь: пишем во временную директорию,
            // а по завершении копируем в MediaStore (registerCompleted).
            val tmpDir = File(context.getExternalFilesDir(null), LEGACY_DIR_NAME)
            tmpDir.mkdirs()
            File(tmpDir, fileName)
        } else {
            val dir = File(context.filesDir, LEGACY_DIR_NAME)
            dir.mkdirs()
            File(dir, fileName)
        }
    }

    /** Вызывается после успешной остановки записи. */
    fun registerCompleted(context: Context, path: String) {
        val file = File(path)
        if (!file.exists()) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
        }
        // На Android 9 и ниже файл уже лежит во внутренней памяти — ничего не делаем.
    }

    fun getAll(context: Context): List<Recording> {
        val result = mutableListOf<Recording>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE
            )
            val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf("$RELATIVE_DIR%"),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    result += Recording(
                        id = id,
                        title = cleanTitle(it.getString(nameCol)),
                        dateMillis = it.getLong(dateCol) * 1000,
                        durationSec = it.getLong(durCol) / 1000,
                        sizeBytes = it.getLong(sizeCol),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        )
                    )
                }
            }
        } else {
            val dir = File(context.filesDir, LEGACY_DIR_NAME)
            val files = dir.listFiles { f -> f.name.endsWith(".m4a") }?.toList()
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            files.forEach { f ->
                result += Recording(
                    id = f.absolutePath.hashCode().toLong(),
                    title = cleanTitle(f.name),
                    dateMillis = f.lastModified(),
                    durationSec = 0,
                    sizeBytes = f.length(),
                    uri = Uri.fromFile(f)
                )
            }
        }
        return result
    }

    fun delete(context: Context, uri: Uri): Boolean = try {
        if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null) >= 0
        } else {
            uri.path?.let { File(it).delete() } == true
        }
    } catch (e: Exception) {
        false
    }

    private fun cleanTitle(fileName: String): String {
        var t = fileName.removeSuffix(".m4a").removeSuffix(".mp4").removeSuffix(".3gp")
        // "Совещание_20261016_120000" -> "Совещание 16.10.2026 12:00"
        val m = Regex("^(.*)_(\\d{8})_(\\d{6})$").find(t)
        if (m != null) {
            val (prefix, d, time) = m.destructured
            val dateStr = try {
                SimpleDateFormat("dd.MM.yyyy", Locale.US).format(
                    SimpleDateFormat("yyyyMMdd", Locale.US).parse(d)!!
                )
            } catch (e: Exception) { d }
            val timeStr = try {
                SimpleDateFormat("HH:mm", Locale.US).format(
                    SimpleDateFormat("HHmmss", Locale.US).parse(time)!!
                )
            } catch (e: Exception) { time }
            t = "$prefix $dateStr $timeStr"
        }
        return t.replace('_', ' ').trim()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f МБ", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%d КБ", bytes / 1_000)
        else -> "$bytes Б"
    }

    fun formatDuration(sec: Long): String =
        String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
}
