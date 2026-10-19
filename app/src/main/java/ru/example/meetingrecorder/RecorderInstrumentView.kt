package ru.example.meetingrecorder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.*

/** Native, display-synchronized instrument. Only the microphone envelope drives its amplitude. */
class RecorderInstrumentView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), Choreographer.FrameCallback {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Path()
    private val echo = Path()
    private val serif = ResourcesCompat.getFont(context, R.font.aurel_serif)
    private val sans = ResourcesCompat.getFont(context, R.font.aurel_sans)
    private val gold = Color.rgb(198, 168, 115)
    private val muted = Color.rgb(148, 152, 137)
    private var state = RecordingService.State.IDLE
    private var elapsed = 0L
    private var targetLevel = 0f
    private var level = 0f
    private var angle = 0f
    private var phase = 0.0
    private var lastFrame = 0L
    private var attached = false
    private var scheduled = false
    private var accessibleSecond = -1L
    private val density = resources.displayMetrics.density

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true; setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun setRecordingState(next: RecordingService.State) {
        state = next
        if (next == RecordingService.State.IDLE) { level = 0f; targetLevel = 0f; angle = 0f; phase = 0.0; elapsed = 0 }
        updateDescription(); invalidate(); schedule()
    }

    fun setSample(elapsedMs: Long, amplitude: Int) {
        elapsed = elapsedMs
        if (state == RecordingService.State.RECORDING) {
            val db = if (amplitude > 0) (20.0 * log10(amplitude / 32767.0)).toFloat() else -60f
            targetLevel = ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
        if (elapsed / 1000 != accessibleSecond) { accessibleSecond = elapsed / 1000; updateDescription() }
        invalidate(); schedule()
    }

    fun visualState() = Bundle().apply {
        putString("state", state.name); putLong("elapsed", elapsed)
        putFloat("level", level); putFloat("angle", angle); putDouble("phase", phase)
    }

    fun restoreVisualState(saved: Bundle) {
        state = runCatching { RecordingService.State.valueOf(saved.getString("state").orEmpty()) }.getOrDefault(RecordingService.State.IDLE)
        elapsed = saved.getLong("elapsed"); level = saved.getFloat("level"); targetLevel = level
        angle = saved.getFloat("angle"); phase = saved.getDouble("phase")
        updateDescription(); invalidate(); schedule()
    }

    private fun updateDescription() {
        val label = context.getString(when (state) { RecordingService.State.RECORDING -> R.string.status_recording; RecordingService.State.PAUSED -> R.string.status_paused; RecordingService.State.SAVING -> R.string.status_saving; else -> R.string.status_idle })
        contentDescription = "$label, ${RecordingRepository.formatDuration(elapsed / 1000)}"
    }

    private fun motionEnabled() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    private fun schedule() {
        if (attached && windowVisibility == VISIBLE && isShown && state == RecordingService.State.RECORDING && !scheduled) {
            scheduled = true; Choreographer.getInstance().postFrameCallback(this)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; lastFrame = 0; schedule() }
    override fun onDetachedFromWindow() { attached = false; scheduled = false; Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) { lastFrame = 0; schedule() } }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); if (visibility == VISIBLE) { lastFrame = 0; schedule() } }
    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!attached || state != RecordingService.State.RECORDING || !isShown || windowVisibility != VISIBLE) { lastFrame = 0; return }
        val dt = if (lastFrame == 0L) 1f / 60 else ((frameTimeNanos - lastFrame) / 1_000_000_000f).coerceIn(0f, .05f)
        lastFrame = frameTimeNanos
        if (motionEnabled()) {
            val easing = 1f - exp(-dt * 5f)
            level += (targetLevel - level) * easing
            angle += (targetLevel * 180 - angle) * (1f - exp(-dt * 3.5f))
            phase += dt * .82
        } else { level = targetLevel; angle = targetLevel * 180 }
        invalidate(); schedule()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val availableWidth = width - 18 * density
        val dialArea = height * .75f
        val size = min(availableWidth, dialArea - 12 * density).coerceAtLeast(1f)
        val left = (width - size) / 2f
        val top = (dialArea - size) / 2f
        canvas.save(); canvas.translate(left, top); canvas.scale(size / 320f, size / 320f)
        drawDial(canvas)
        canvas.restore()
        drawWave(canvas, dialArea, height - dialArea)
    }

    private fun paint(color: Int, stroke: Float = 0f) {
        ink.shader = null; ink.color = color; ink.alpha = 255; ink.strokeWidth = stroke
        ink.style = if (stroke > 0) Paint.Style.STROKE else Paint.Style.FILL
        ink.strokeCap = Paint.Cap.ROUND
    }
    private fun text(canvas: Canvas, value: String, y: Float, size: Float, color: Int, face: Typeface? = serif, x: Float = 160f) {
        paint(color); ink.typeface = face; ink.textSize = size; ink.textAlign = Paint.Align.CENTER; canvas.drawText(value, x, y, ink)
    }
    private fun drawDial(c: Canvas) {
        paint(gold); ink.shader = LinearGradient(40f, 20f, 280f, 300f, intArrayOf(0xff967d50.toInt(), 0xffddc38b.toInt(), 0xff54452d.toInt(), 0xffbea171.toInt()), floatArrayOf(0f,.27f,.64f,1f), Shader.TileMode.CLAMP)
        c.drawCircle(160f,160f,156f,ink)
        paint(0xff131716.toInt()); ink.shader = RadialGradient(135f,100f,230f,intArrayOf(0xff292a23.toInt(),0xff151918.toInt(),0xff0b100f.toInt()),floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(160f,160f,151f,ink)
        paint(0xffa48a57.toInt(),.7f);c.drawCircle(160f,160f,149f,ink)
        paint(0xff6d5c3e.toInt(),.35f);c.drawCircle(160f,160f,143f,ink)
        paint(0xff9d8353.toInt(),.5f); c.drawArc(36f,36f,284f,284f,160f,220f,false,ink)
        for (n in 0..50) {
            val a = Math.toRadians(-200 + n * 4.4)
            val radius = if(n % 5 == 0) 112f else 117f
            paint(if(n > 44) 0xffc0a071.toInt() else 0xffb89d69.toInt(), if(n%5==0) .95f else .45f)
            c.drawLine(160+(cos(a)*radius).toFloat(),160+(sin(a)*radius).toFloat(),160+(cos(a)*124).toFloat(),160+(sin(a)*124).toFloat(),ink)
        }
        listOf("−40","−20","−10","−5","0").forEachIndexed { i,label ->
            val a = Math.toRadians(-190.0 + i*45)
            text(c,label,163+(sin(a)*99).toFloat(),7.5f,0xffb5a27e.toInt(),sans,160+(cos(a)*99).toFloat())
        }
        text(c,"VU",113f,25f,0xffc8b486.toInt())
        c.save();c.rotate(angle,160f,160f)
        paint(0xffdec58e.toInt(),1.4f);c.drawLine(160f,160f,45f,180f,ink)
        paint(0xffa38958.toInt(),2f);c.drawLine(160f,160f,177f,157f,ink);c.restore()
        paint(0xff101514.toInt());c.drawCircle(160f,160f,8f,ink)
        paint(0xffbb9a5f.toInt(),.8f);c.drawCircle(160f,160f,8f,ink)
        paint(0xffd4b67c.toInt());c.drawCircle(160f,160f,2.5f,ink)
        paint(0xff78613c.toInt(),.5f);c.drawLine(141f,209f,179f,209f,ink)
        val seconds = elapsed/1000
        val clock = RecordingRepository.formatDuration(seconds)
        text(c,clock,250f,34f,0xffe8dab9.toInt())
        text(c,String.format(java.util.Locale.US,"· %02d",(elapsed%1000)/10),270f,9f,0xff9c8c68.toInt(),sans)
        val label = context.getString(when(state){RecordingService.State.RECORDING->R.string.status_recording;RecordingService.State.PAUSED->R.string.status_paused;RecordingService.State.SAVING->R.string.status_saving;else->R.string.status_idle})
        text(c,label,286f,6.5f,if(state==RecordingService.State.RECORDING)gold else muted,sans)
    }
    private fun drawWave(c: Canvas, top: Float, area: Float) {
        val inset=12*density;val span=width-inset*2;val center=top+area*.47f
        paint(0xff564d37.toInt(),.35f*density);c.drawLine(inset,center,width-inset,center,ink)
        wave.rewind();echo.rewind()
        val amplitude=area*.36f*level
        for(i in 0..160){
            val u=i/160.0;val x=inset+span*u.toFloat();val envelope=.1+.9*sin(u*PI).pow(1.5)
            val y=center+((sin(u*25-phase*2.2)*.58+sin(u*45+phase*1.3)*.25+sin(u*71-phase*.7)*.12)*amplitude*envelope).toFloat()
            val y2=center+((sin(u*25-phase*2.2+.6)*.57+sin(u*43+phase)*.22)*amplitude*.64*envelope).toFloat()
            if(i==0){wave.moveTo(x,y);echo.moveTo(x,y2)}else{wave.lineTo(x,y);echo.lineTo(x,y2)}
        }
        paint(gold,1.15f*density);ink.shader=LinearGradient(inset,0f,width-inset,0f,intArrayOf(0x0080653c,0xffa98a53.toInt(),0xffe6ce96.toInt(),0xffa98a53.toInt(),0x0080653c),floatArrayOf(0f,.18f,.5f,.82f,1f),Shader.TileMode.CLAMP)
        c.drawPath(wave,ink);ink.strokeWidth=.65f*density;ink.alpha=75;c.drawPath(echo,ink);ink.alpha=255;ink.shader=null
    }
}
