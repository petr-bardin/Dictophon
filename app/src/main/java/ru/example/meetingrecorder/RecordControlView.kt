package ru.example.meetingrecorder

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class RecordControlView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var recordingState: RecordingService.State = RecordingService.State.IDLE
        set(value) { field = value; contentDescription = context.getString(when(value){RecordingService.State.RECORDING->R.string.btn_pause;RecordingService.State.PAUSED->R.string.btn_resume;else->R.string.btn_record}); invalidate() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save();canvas.translate(width/2f,height/2f);canvas.scale(minOf(width,height)/64f,minOf(width,height)/64f)
        ink.style=Paint.Style.STROKE;ink.strokeWidth=.65f;ink.color=0xffb39660.toInt();canvas.drawCircle(0f,0f,30f,ink)
        ink.style=Paint.Style.FILL;ink.shader=LinearGradient(-23f,-24f,24f,25f,intArrayOf(0xffecd3a0.toInt(),0xffac8850.toInt(),0xffd9ba7f.toInt()),null,Shader.TileMode.CLAMP);canvas.drawCircle(0f,0f,27f,ink);ink.shader=null
        ink.style=Paint.Style.STROKE;ink.strokeWidth=.5f;ink.color=0xff745c38.toInt()
        for(n in 0 until 100){val a=n*Math.PI/50;canvas.drawLine((cos(a)*24).toFloat(),(sin(a)*24).toFloat(),(cos(a)*26.5).toFloat(),(sin(a)*26.5).toFloat(),ink)}
        ink.strokeWidth=.7f;canvas.drawCircle(0f,0f,22f,ink);ink.color=0xffe6cb93.toInt();canvas.drawCircle(0f,0f,21f,ink)
        ink.color=0xff30382a.toInt();ink.strokeWidth=1.6f;ink.strokeCap=Paint.Cap.ROUND
        when(recordingState){
            RecordingService.State.RECORDING->{canvas.drawLine(-4f,-7f,-4f,7f,ink);canvas.drawLine(4f,-7f,4f,7f,ink)}
            RecordingService.State.PAUSED->{val path=Path();path.moveTo(-4f,-7f);path.lineTo(7f,0f);path.lineTo(-4f,7f);path.close();canvas.drawPath(path,ink)}
            else->{canvas.drawRoundRect(-3.5f,-9f,3.5f,3f,3.5f,3.5f,ink);canvas.drawArc(-6f,-5f,6f,7f,0f,180f,false,ink);canvas.drawLine(0f,7f,0f,11f,ink);canvas.drawLine(-3f,11f,3f,11f,ink)}
        }
        if(!isEnabled){ink.style=Paint.Style.FILL;ink.color=0x7730392d;canvas.drawCircle(0f,0f,30f,ink)}
        if(isFocused){ink.style=Paint.Style.STROKE;ink.strokeWidth=1f;ink.color=0xfff1daab.toInt();canvas.drawCircle(0f,0f,31f,ink)}
        canvas.restore()
    }
}
