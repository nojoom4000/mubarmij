package com.ftth.ontreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator

/** عداد دائري لقوة الإشارة RX */
class GaugeView(ctx: Context) : View(ctx) {
    var minV = -35f
    var maxV = 0f
    private var shown = minV
    private var arcColor = Color.parseColor("#475569")
    private var valueText = "--"
    private var status = "اضغط زر التحديث للقراءة"
    var title = "قوة الإشارة RX"
    var unit = "dBm"

    private val d = resources.displayMetrics.density
    private val stroke = 18 * d
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#22314A")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 46 * d
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B9BB4"); textAlign = Paint.Align.CENTER; textSize = 14 * d
    }
    private val stat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 15 * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val oval = RectF()
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // لتأثير التوهج
    }

    private fun radius(w: Int) = minOf(w * 0.36f, 135 * d)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val r = radius(w)
        setMeasuredDimension(w, (stroke + r + r * 0.6f + stroke + 34 * d).toInt())
    }

    fun setReading(v: Float?, text: String, color: Int, st: String) {
        valueText = text
        status = st
        arcColor = color
        animator?.cancel()
        val to = (v ?: minV).coerceIn(minV, maxV)
        animator = ValueAnimator.ofFloat(minV, to).apply {
            duration = 1200
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun angleOf(v: Float) = 150f + 240f * ((v - minV) / (maxV - minV)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val r = radius(width)
        val cx = width / 2f
        val cy = stroke + r
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, 150f, 240f, false, track)

        val sweep = angleOf(shown) - 150f
        if (sweep > 0.5f) {
            arc.color = arcColor
            arc.setShadowLayer(14 * d, 0f, 0f, arcColor)
            c.drawArc(oval, 150f, sweep, false, arc)
        }

        // علامات الحدود: -27 (أحمر) ، -25 (برتقالي) ، -8 (برتقالي)
        listOf(-27f to "#EF4444", -25f to "#F59E0B", -8f to "#F59E0B").forEach { (v, col) ->
            val a = Math.toRadians(angleOf(v).toDouble())
            val r1 = r - stroke * 0.95f
            val r2 = r - stroke * 1.55f
            tick.color = Color.parseColor(col)
            c.drawLine(
                cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(),
                cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), tick
            )
        }

        c.drawText(title, cx, cy - r * 0.36f, small)
        c.drawText(valueText, cx, cy + big.textSize * 0.32f, big)
        c.drawText(unit, cx, cy + r * 0.36f, small)
        stat.color = arcColor
        c.drawText(status, cx, height - 10 * d, stat)
    }
}

/** شريط تقدم أفقي متحرك */
class BarView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var frac = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22314A") }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        fg.color = Color.parseColor("#38BDF8")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (7 * d).toInt())
    }

    fun set(f: Float, color: Int) {
        fg.color = color
        ValueAnimator.ofFloat(0f, f.coerceIn(0f, 1f)).apply {
            duration = 1000
            interpolator = DecelerateInterpolator()
            addUpdateListener { frac = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(rect, h / 2, h / 2, bg)
        if (frac > 0f) {
            val w = width * frac
            // يبدأ من اليمين في الواجهة العربية
            if (layoutDirection == LAYOUT_DIRECTION_RTL) rect.set(width - w, 0f, width.toFloat(), h)
            else rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, h / 2, h / 2, fg)
        }
    }
}
