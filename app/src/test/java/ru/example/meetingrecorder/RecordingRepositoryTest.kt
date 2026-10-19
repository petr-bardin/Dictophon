package ru.example.meetingrecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RecordingRepositoryTest {
    @Test fun inaccessibleMediaStoreKeepsAudioPlayableInLocalArchive() {
        val app=RuntimeEnvironment.getApplication()
        val unavailable=object:ContentProvider(){
            override fun onCreate()=true
            override fun insert(uri:Uri,values:ContentValues?):Uri?=error("MediaStore unavailable")
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=error("MediaStore unavailable")
            override fun getType(uri:Uri):String?=null
            override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
            override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        }
        unavailable.attachInfo(app,ProviderInfo().apply{authority=MediaStore.AUTHORITY})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,unavailable)
        val pending=RecordingRepository.createFile(app,null)
        val bytes=ByteArray(128){it.toByte()};pending.writeBytes(bytes)
        val uri=RecordingRepository.registerCompleted(app,pending.absolutePath,"Встреча 19.10")
        assertEquals("file",uri.scheme)
        val audio=File(uri.path!!)
        assertTrue(audio.exists());assertArrayEquals(bytes,audio.readBytes());assertFalse(pending.exists())
        val row=RecordingRepository.getAll(app).single{it.uri==uri}
        assertEquals("Встреча 19.10",row.title)
        RecordingRepository.saveMarkers(app,uri,listOf(1000L,2500L))
        assertEquals(listOf(1000L,2500L),RecordingRepository.markers(app,uri))
        assertTrue(RecordingRepository.delete(app,uri));assertFalse(audio.exists())
        assertTrue(RecordingRepository.markers(app,uri).isEmpty())
    }
}
