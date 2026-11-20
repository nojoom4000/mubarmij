package com.ftth.ontreader

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** شاشة ترحيب: اسم المكتب وشعار متحرك، تنتقل تلقائياً إلى الشاشة الرئيسية */
class SplashActivity : Activity() {

    private val BG = Color.parseColor("#0B1220")
    private val TEXT = Color.parseColor("#F1F5F9")
    private val MUTED = Color.parseColor("#8B9BB4")
    private val ACCENT = Color.parseColor("#38BDF8")
    private val ACCENT2 = Color.parseColor("#818CF8")

    private fun dpf(v: Int) = v * resources.displayMetrics.density
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val logo = LogoView(this)
        col.addView(logo, LinearLayout.LayoutParams(dp(140), dp(140)))

        val office = TextView(this).apply {
            text = "مكتب المبرمج"
            textSize = 30f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        col.addView(office, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })

        val sub = TextView(this).apply {
            text = "لخدمات الإنترنت"
            textSize = 16f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
        }
        col.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // خط فاصل صغير
        val line = View(this).apply { setBackgroundColor(Color.parseColor("#22314A")) }
        col.addView(line, LinearLayout.LayoutParams(dp(60), dp(2)).apply { topMargin = dp(20) })

        val app = TextView(this).apply {
            text = "قارئ أجهزة ONT"
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        col.addView(app, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val footer = TextView(this).apply {
            text = "جميع الحقوق محفوظة"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        root.addView(footer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(28)
        })

        setContentView(root)

        // ظهور تدريجي للنصوص
        listOf<View>(office, sub, line, app).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dpf(16)
            v.animate().alpha(1f).translationY(0f).setStartDelay(260L + i * 120L)
                .setDuration(500).setInterpolator(DecelerateInterpolator()).start()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }, 2100)
    }

    /** شعار دائري متحرك يحاكي إشارة البث */
    inner class LogoView(ctx: android.content.Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private var phase = 0f
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val oval = RectF()

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            // ثلاث أقواس بث تتوهج بالتتابع
            for (i in 0..2) {
                val r = (18 + i * 20) * d
                val local = ((phase * 3f) - i).coerceIn(0f, 1f)
                val alpha = (80 + 175 * (1f - kotlin.math.abs(local - 0.5f) * 2f)).toInt().coerceIn(40, 255)
                ring.color = Color.argb(alpha, 56, 189, 248)
                ring.strokeWidth = 5 * d
                oval.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(oval, 215f, 110f, false, ring)
            }
            // النقطة المركزية مع توهج
            dot.setShadowLayer(16 * d, 0f, 0f, ACCENT2)
            c.drawCircle(cx, cy, 11 * d, dot)
        }
    }
}
