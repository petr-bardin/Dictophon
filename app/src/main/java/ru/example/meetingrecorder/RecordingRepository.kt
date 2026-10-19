package ru.example.meetingrecorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(val id: Long, val title: String, val dateMillis: Long, val durationSec: Long, val sizeBytes: Long, val uri: Uri)

object RecordingRepository {
    private const val RELATIVE_DIR = "Music/MeetingRecorder"
    private fun localDir(context: Context) = File(if (Build.VERSION.SDK_INT >= 29) context.getExternalFilesDir(null) ?: context.filesDir else context.filesDir, "recordings").apply { mkdirs() }
    private fun safeTitle(title: String?) = title?.replace(Regex("[^\\p{L}\\p{N} .,_()-]"), "")?.trim()?.take(80)?.ifEmpty { null }

    fun createFile(context: Context, title: String?): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val pending = File(localDir(context).parentFile, "pending_recordings").apply { mkdirs() }
        return File(pending, "${safeTitle(title) ?: "Запись"}_$stamp.m4a")
    }

    /** Keep a durable local file until publication succeeds. Failed publication remains playable in the archive. */
    fun registerCompleted(context: Context, path: String, title: String? = null): Uri {
        val source = File(path)
        check(source.exists() && source.length() > 0) { "No recorded audio" }
        val stamp = Regex("(\\d{8}_\\d{6}(?:_\\d{3})?)\\.m4a$").find(source.name)?.groupValues?.get(1)
        val name = safeTitle(title)?.let { "${it}_${stamp ?: System.currentTimeMillis()}.m4a" } ?: source.name
        val durable = File(localDir(context), name)
        if (source.absolutePath != durable.absolutePath) {
            if (!source.renameTo(durable)) { source.copyTo(durable, overwrite = false); source.delete() }
        }
        if (Build.VERSION.SDK_INT < 29) return Uri.fromFile(durable)
        val resolver = context.contentResolver
        var published: Uri? = null
        try {
            val retriever = MediaMetadataRetriever()
            val durationMs = try { retriever.setDataSource(durable.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } catch (_: Exception) { null } finally { retriever.release() }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, durable.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                durationMs?.let { put(MediaStore.Audio.Media.DURATION, it) }
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
            published = uri
            checkNotNull(resolver.openOutputStream(uri)).use { output -> durable.inputStream().use { it.copyTo(output) } }
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0)
            durable.delete()
            return uri
        } catch (e: Exception) {
            Log.w("RecordingRepository", "MediaStore publication failed; keeping local audio", e)
            published?.let { runCatching { resolver.delete(it, null, null) } }
            return Uri.fromFile(durable)
        }
    }

    fun getAll(context: Context): List<Recording> {
        val items = mutableListOf<Recording>()
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE)
            try {
                context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                    "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0", arrayOf("$RELATIVE_DIR/%"), "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        items += Recording(id, cleanTitle(c.getString(1)), c.getLong(2) * 1000, c.getLong(3) / 1000, c.getLong(4), ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
                    }
                }
            } catch (e: Exception) {
                Log.w("RecordingRepository", "MediaStore unavailable; showing local recordings", e)
            }
        }
        localDir(context).listFiles { f -> f.extension == "m4a" && f.length() > 0 }?.forEach { f ->
            val retriever = MediaMetadataRetriever()
            val duration = try { retriever.setDataSource(f.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000) ?: 0 } catch (_: Exception) { 0 } finally { retriever.release() }
            items += Recording(f.absolutePath.hashCode().toLong(), cleanTitle(f.name), f.lastModified(), duration, f.length(), Uri.fromFile(f))
        }
        return items.sortedByDescending { it.dateMillis }
    }

    fun saveMarkers(context: Context, uri: Uri, markers: List<Long>) {
        context.getSharedPreferences("recording_metadata", Context.MODE_PRIVATE).edit().putString("markers:$uri", JSONArray(markers).toString()).apply()
    }

    fun markers(context: Context, uri: Uri): List<Long> = runCatching {
        val values = JSONArray(context.getSharedPreferences("recording_metadata", Context.MODE_PRIVATE).getString("markers:$uri", "[]"))
        List(values.length()) { values.getLong(it) }
    }.getOrDefault(emptyList())

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        val deleted = if (uri.scheme == "content") context.contentResolver.delete(uri, null, null) > 0 else uri.path?.let { File(it).delete() } == true
        if (deleted) context.getSharedPreferences("recording_metadata", Context.MODE_PRIVATE).edit().remove("markers:$uri").apply()
        deleted
    }.getOrDefault(false)

    private fun cleanTitle(name: String): String {
        val stem = name.removeSuffix(".m4a").removeSuffix(".mp4").removeSuffix(".3gp")
        val match = Regex("^(.*)_(\\d{8})_(\\d{6})(?:_\\d{3})?$").find(stem) ?: return stem.replace('_', ' ')
        val (prefix, date, time) = match.destructured
        return if (prefix == "Запись" || prefix == "Совещание") {
            val instant = runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(date + time) }.getOrNull()
            if (instant != null) "$prefix ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(instant)}" else prefix
        } else prefix.replace('_', ' ')
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} КБ"
        else -> "$bytes Б"
    }
    fun formatDuration(sec: Long): String = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
}
