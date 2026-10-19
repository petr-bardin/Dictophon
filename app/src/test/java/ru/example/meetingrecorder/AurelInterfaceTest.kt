package ru.example.meetingrecorder

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ImageButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AurelInterfaceTest {
    private lateinit var serviceController: ServiceController<RecordingService>
    @Before fun connectRealServiceBinder() {
        serviceController = Robolectric.buildService(RecordingService::class.java).create()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, RecordingService::class.java), serviceController.get().onBind(Intent()))
    }
    @After fun destroyService() { serviceController.destroy() }

    @Test fun archiveAndStudioNavigationKeepTheMainControlsReachable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val root = activity.findViewById<View>(android.R.id.content)
            layout(root,390,780)
            val instrument = activity.findViewById<RecorderInstrumentView>(R.id.instrument)
            assertTrue(instrument.height > root.height * .85)
            val record = activity.findViewById<View>(R.id.btnRecord)
            val archive = activity.findViewById<ImageButton>(R.id.btnArchive)
            assertTrue(record.bottom <= root.height)
            assertTrue(archive.width >= 48)
            capture(root,"studio")
            archive.performClick()
            assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.archiveScreen).visibility)
            assertEquals(View.GONE,activity.findViewById<View>(R.id.studioScreen).visibility)
            layout(root,390,780); capture(root,"archive")
            activity.findViewById<View>(R.id.btnStudio).performClick()
            assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.studioScreen).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativeInstrumentDrawsAtSmallAndLargePhoneSizesAndFreezesOnPause() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val root = activity.findViewById<View>(android.R.id.content)
            val instrument = activity.findViewById<RecorderInstrumentView>(R.id.instrument)
            for ((width,height) in listOf(320 to 568,390 to 780,480 to 900)) {
                layout(root,width,height)
                instrument.setRecordingState(RecordingService.State.RECORDING)
                instrument.setSample(14_470,22_000)
                repeat(120) { instrument.doFrame((it+1)*16_666_667L) }
                instrument.setRecordingState(RecordingService.State.PAUSED)
                activity.findViewById<RecordControlView>(R.id.btnRecord).recordingState = RecordingService.State.PAUSED
                capture(root,"paused-${width}x$height")
                val before = capture(instrument,"signal-before-${width}x$height")
                repeat(20) { instrument.doFrame((it+130)*16_666_667L) }
                val after = capture(instrument,"signal-after-${width}x$height")
                assertTrue("Paused signal must stay still",before.sameAs(after))
                val pixel = before.getPixel(instrument.width/2,instrument.height/2)
                assertTrue("Instrument should draw an opaque face",pixel ushr 24 > 0)
            }
        } finally { controller.pause().stop().destroy() }
    }
    private fun layout(view: View,width: Int,height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
    private fun capture(view: View,name: String): Bitmap {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file=File("build/reports/aurel/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        return bitmap
    }
}
