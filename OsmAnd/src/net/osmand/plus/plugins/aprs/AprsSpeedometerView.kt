package net.osmand.plus.plugins.aprs

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import net.osmand.plus.R
import net.osmand.shared.aprs.AprsSpeed

/** APRSISMO-style live GPS speed readout, implemented natively for Android. */
object AprsMotion {
    @Volatile var speed: Double? = null
    @Volatile var at: Long = 0
    fun update(location: android.location.Location) {
        speed = if (location.hasSpeed()) location.speed.toDouble() else null
        at = location.elapsedRealtimeNanos / 1_000_000
    }
    fun update(location: net.osmand.Location) {
        speed = if (location.hasSpeed()) location.speed.toDouble() else null
        at = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - location.time).coerceAtLeast(0)
    }
}

class AprsSpeedometerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prefs = context.getSharedPreferences("aprs", Context.MODE_PRIVATE)
    private var metric = prefs.getBoolean("speed_metric", false)
    init {
        contentDescription = context.getString(R.string.aprs_speedometer)
        setOnClickListener {
            metric = !metric
            prefs.edit().putBoolean("speed_metric", metric).apply()
            invalidate()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = width / 150f
        canvas.save(); canvas.scale(scale, scale)
        paint.color = Color.rgb(20, 30, 42); paint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, 150f, 96f, 18f, 18f, paint)
        val speed = AprsSpeed.value(AprsMotion.speed, SystemClock.elapsedRealtime() - AprsMotion.at, metric)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.DKGRAY
        canvas.drawArc(21f, 10f, 129f, 118f, 195f, 150f, false, paint)
        paint.color = Color.CYAN
        canvas.drawArc(21f, 10f, 129f, 118f, 195f,
            150f * ((speed ?: 0).toFloat() / if(metric) 200f else 120f).coerceIn(0f, 1f), false, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
        paint.textSize = 37f; paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(speed?.toString() ?: "—", 75f, 61f, paint)
        paint.textSize = 12f; paint.typeface = Typeface.DEFAULT
        canvas.drawText(if(speed == null) context.getString(R.string.aprs_speed_waiting) else if(metric) "km/h" else "mph", 75f, 82f, paint)
        canvas.restore()
    }
}
