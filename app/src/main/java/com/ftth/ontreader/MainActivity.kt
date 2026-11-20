package com.ftth.ontreader

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    // ---------------- الألوان ----------------
    private val BG = Color.parseColor("#0B1220")
    private val CARD = Color.parseColor("#141D2E")
    private val CARD2 = Color.parseColor("#1B263A")
    private val TEXT = Color.parseColor("#F1F5F9")
    private val MUTED = Color.parseColor("#8B9BB4")
    private val ACCENT = Color.parseColor("#38BDF8")
    private val ACCENT2 = Color.parseColor("#818CF8")
    private val GOOD = Color.parseColor("#22C55E")
    private val WARN = Color.parseColor("#F59E0B")
    private val BAD = Color.parseColor("#EF4444")
    private val OFF = Color.parseColor("#475569")

    private val REQ_SAVE = 7001
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var profiles: MutableList<Profile>
    private var selected = -1 // -1 = تلقائي

    // عناصر الواجهة
    private lateinit var content: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var autoChip: TextView
    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvSub: TextView
    private lateinit var gauge: GaugeView
    private lateinit var tvTx: TextView
    private lateinit var tvVolt: TextView
    private lateinit var tvBias: TextView
    private lateinit var portsRow: LinearLayout
    private lateinit var tvPortsHint: TextView
    private lateinit var devBox: LinearLayout
    private lateinit var errorCard: LinearLayout
    private lateinit var tvError: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var fab: TextView
    private val tiles = HashMap<String, Triple<TextView, BarView, TextView>>()
    private val animCards = ArrayList<View>()

    private var fabSpin: ObjectAnimator? = null
    private var dotPulse: ObjectAnimator? = null
    private var client: HuaweiClient? = null
    private var clientProfile: Profile? = null
    @Volatile private var busy = false
    private var autoOn = false
    private var lastDump = ""
    private val autoInterval = 15_000L

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (autoOn) {
                readNow()
                ui.postDelayed(this, autoInterval)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    // ---------------- أدوات رسم ----------------
    private fun rounded(color: Int, r: Int, stroke: Int? = null, sw: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(r)
        if (stroke != null) setStroke(dp(sw), stroke)
    }

    private fun gradient(r: Int, vararg colors: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dpf(r) }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun mlp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 22)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        elevation = dpf(2)
    }

    private fun cardTitle(parent: LinearLayout, icon: String, t: String) {
        parent.addView(tv("$icon  $t", 15f, TEXT, true), mlp().apply { bottomMargin = dp(8) })
    }

    // ---------------- بناء الشاشة ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = BG
        window.navigationBarColor = BG
        profiles = ProfileStore.load(this)
        selected = getSharedPreferences("cfg", MODE_PRIVATE).getInt("sel", -1).let { if (it >= profiles.size) -1 else it }

        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(110))
        }
        scroll.addView(content)
        frame.addView(scroll)
        setContentView(frame)

        buildHeader()
        buildProfiles()
        buildGauge()
        buildTiles()
        buildPorts()
        buildDevice()
        buildError()
        buildFooter()
        buildFab(frame)
    }

    private fun buildHeader() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv("ONT Reader", 26f, TEXT, true))
        tvSub = tv("فحص أجهزة الفايبر بلمسة", 13f, MUTED)
        col.addView(tvSub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD2, 20)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OFF) } }
        pill.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        tvStatus = tv("غير متصل", 12f, MUTED, true)
        pill.addView(tvStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) })
        row.addView(pill)
        content.addView(row)
    }

    private fun buildProfiles() {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(chipsRow)
        content.addView(hs, mlp(18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv("اضغط مطولاً على أي ملف لتعديله", 11f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        autoChip = chip("⟳ تحديث تلقائي", false)
        autoChip.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            autoOn = !autoOn
            styleChip(autoChip, autoOn)
            ui.removeCallbacks(autoRunnable)
            if (autoOn) {
                ui.post(autoRunnable)
                Toast.makeText(this, "تحديث كل 15 ثانية", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(autoChip)
        content.addView(row, mlp(8))
        renderChips()
    }

    private fun chip(text: String, on: Boolean): TextView = tv(text, 13f, TEXT, true).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        styleChip(this, on)
    }

    private fun styleChip(c: TextView, on: Boolean) {
        c.background = if (on) gradient(20, ACCENT, ACCENT2) else rounded(CARD2, 20, Color.parseColor("#2A3850"))
        c.setTextColor(if (on) Color.parseColor("#0B1220") else TEXT)
    }

    private fun renderChips() {
        chipsRow.removeAllViews()
        fun add(text: String, on: Boolean, click: () -> Unit, long: (() -> Unit)?) {
            val c = chip(text, on)
            c.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); click() }
            if (long != null) c.setOnLongClickListener { long(); true }
            chipsRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        add("⚡ تلقائي", selected == -1, { select(-1) }, null)
        profiles.forEachIndexed { i, p -> add(p.name, selected == i, { select(i) }, { editProfile(i) }) }
        add("+ إضافة", false, { editProfile(-1) }, null)
    }

    private fun select(i: Int) {
        selected = i
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("sel", i).apply()
        client = null
        renderChips()
        val msg = if (i == -1) "كشف تلقائي لنوع الجهاز" else "${profiles[i].name} • ${profiles[i].host}"
        tvSub.text = msg
    }

    private fun buildGauge() {
        val c = card()
        gauge = GaugeView(this)
        c.addView(gauge, mlp())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun mini(label: String): TextView {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = rounded(CARD2, 14)
                setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            val v = tv("--", 17f, TEXT, true).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
            box.addView(v)
            box.addView(tv(label, 11f, MUTED).apply { gravity = Gravity.CENTER })
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            return v
        }
        tvTx = mini("البور المرسل TX")
        tvVolt = mini("الجهد")
        tvBias = mini("تيار الليزر")
        c.addView(row, mlp(6))
        content.addView(c, mlp(16))
        animCards.add(c)
    }

    private fun buildTiles() {
        val defs = listOf(
            listOf("temp" to "🌡  درجة الحرارة", "cpu" to "⚙  المعالج CPU"),
            listOf("ram" to "▦  الذاكرة RAM", "up" to "⏱  مدة التشغيل")
        )
        for (pair in defs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { idx, (key, title) ->
                val c = card().apply { setPadding(dp(14), dp(12), dp(14), dp(14)) }
                c.addView(tv(title, 12f, MUTED))
                val v = tv("--", 24f, TEXT, true).apply { textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.START }
                c.addView(v, mlp(4))
                val sub = tv(" ", 11f, MUTED)
                c.addView(sub)
                val bar = BarView(this)
                c.addView(bar, mlp(8))
                tiles[key] = Triple(v, bar, sub)
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx == 0) marginEnd = dp(6) else marginStart = dp(6)
                })
                animCards.add(c)
            }
            content.addView(row, mlp(12))
        }
    }

    private fun buildPorts() {
        val c = card()
        cardTitle(c, "⇄", "منافذ الشبكة LAN")
        portsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        c.addView(portsRow, mlp())
        tvPortsHint = tv("GE = جيجابت 1000M   •   FE = فاست 100M", 11f, MUTED)
        c.addView(tvPortsHint, mlp(10))
        content.addView(c, mlp(12))
        animCards.add(c)
        renderPorts(emptyList())
    }

    private fun buildDevice() {
        val c = card()
        cardTitle(c, "ⓘ", "معلومات الجهاز")
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(devBox)
        content.addView(c, mlp(12))
        animCards.add(c)
        renderDevice(null, null)
    }

    private fun buildError() {
        errorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#2A1416"), 18, BAD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        errorCard.addView(tv("⚠  تعذرت القراءة", 15f, Color.parseColor("#FCA5A5"), true))
        tvError = tv("", 13f, TEXT)
        errorCard.addView(tvError, mlp(4))
        errorCard.addView(tv("عرض التفاصيل ›", 13f, ACCENT, true).apply {
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { showDump() }
        })
        content.addView(errorCard, 1, mlp(14)) // تحت العنوان مباشرة
    }

    private fun buildFooter() {
        tvUpdated = tv("لم تتم أي قراءة بعد", 12f, MUTED).apply { gravity = Gravity.CENTER }
        content.addView(tvUpdated, mlp(16))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, click: () -> Unit) {
            val b = tv(t, 13f, TEXT, true).apply {
                gravity = Gravity.CENTER
                background = rounded(CARD2, 14, Color.parseColor("#2A3850"))
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { click() }
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        btn("{ }  البيانات الخام") { showDump() }
        btn("💾  حفظ ملف للتشخيص") { saveDumpFile() }
        content.addView(row, mlp(10))
    }

    private fun buildFab(frame: FrameLayout) {
        fab = tv("⟳", 30f, Color.parseColor("#0B1220"), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT2)).apply { shape = GradientDrawable.OVAL }
            elevation = dpf(10)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                it.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }.start()
                readNow()
            }
        }
        frame.addView(fab, FrameLayout.LayoutParams(dp(66), dp(66)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dp(22); bottomMargin = dp(26)
        })
    }

    // ---------------- عرض البيانات ----------------
    private fun num(s: String?): Double? = s?.let { Regex("""-?\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

    private fun countUp(t: TextView, target: Double?, decimals: Int, suffix: String, raw: String?) {
        if (target == null) { t.text = raw ?: "--"; return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = target * (it.animatedValue as Float)
                t.text = String.format(Locale.US, "%.${decimals}f", v) + suffix
            }
            start()
        }
    }

    private fun pctColor(v: Double?) = when {
        v == null -> OFF
        v < 60 -> GOOD
        v < 85 -> WARN
        else -> BAD
    }

    private fun formatUptime(raw: String?): String? {
        val s = raw?.trim()?.toLongOrNull() ?: return raw
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}ي ${h}س"
            h > 0 -> "${h}س ${m}د"
            else -> "${m}د"
        }
    }

    private fun show(r: OntReading, p: Profile) {
        errorCard.visibility = View.GONE
        setConnected(true, p)

        // الإشارة
        val rx = num(r.rxPower)
        val (rxColor, rxText) = when {
            rx == null -> OFF to "لا توجد إشارة — افحص الفايبر"
            rx > -8 -> WARN to "قوية جداً"
            rx >= -25 -> GOOD to "إشارة ممتازة"
            rx >= -27 -> WARN to "إشارة ضعيفة"
            else -> BAD to "ضعيفة جداً — افحص الكيبل والوصلات"
        }
        gauge.setReading(rx?.toFloat(), rx?.let { String.format(Locale.US, "%.2f", it) } ?: "--", rxColor, rxText)

        val tx = num(r.txPower)
        countUp(tvTx, tx, 2, " dBm", r.txPower)
        tvTx.setTextColor(if (tx == null) TEXT else if (tx in 0.5..5.0) GOOD else WARN)
        val volt = num(r.voltage)
        if (volt != null && volt > 100) countUp(tvVolt, volt / 1000.0, 2, " V", r.voltage)
        else countUp(tvVolt, volt, 2, " V", r.voltage)
        countUp(tvBias, num(r.bias), 1, " mA", r.bias)

        // البطاقات
        tiles["temp"]?.let { (v, bar, sub) ->
            val t = num(r.temperature)
            countUp(v, t, 0, " °C", r.temperature)
            val col = when { t == null -> OFF; t < 60 -> GOOD; t < 75 -> WARN; else -> BAD }
            v.setTextColor(col)
            sub.text = when { t == null -> "غير متوفرة"; t < 60 -> "طبيعية"; t < 75 -> "مرتفعة"; else -> "مرتفعة جداً" }
            bar.set(((t ?: 0.0) / 90.0).toFloat(), col)
        }
        tiles["cpu"]?.let { (v, bar, sub) ->
            val c = num(r.cpu)
            countUp(v, c, 0, " %", r.cpu)
            v.setTextColor(pctColor(c))
            sub.text = if (c == null) "غير متوفر" else "نسبة الاستخدام"
            bar.set(((c ?: 0.0) / 100).toFloat(), pctColor(c))
        }
        tiles["ram"]?.let { (v, bar, sub) ->
            val m = num(r.memory)
            countUp(v, m, 0, " %", r.memory)
            v.setTextColor(pctColor(m))
            sub.text = if (m == null) "غير متوفرة" else "نسبة الاستخدام"
            bar.set(((m ?: 0.0) / 100).toFloat(), pctColor(m))
        }
        tiles["up"]?.let { (v, bar, sub) ->
            v.text = formatUptime(r.uptime) ?: "--"
            v.setTextColor(TEXT)
            sub.text = "منذ آخر إعادة تشغيل"
            bar.set(if (r.uptime != null) 1f else 0f, ACCENT2)
        }

        renderPorts(r.ports)
        renderDevice(r, p)

        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvUpdated.text = "آخر تحديث $time  •  ${p.name}"
        animateIn()
    }

    private fun renderPorts(ports: List<PortInfo>) {
        portsRow.removeAllViews()
        val list = if (ports.isEmpty()) (1..4).map { null } else ports
        list.forEachIndexed { i, p ->
            val up = p?.isUp == true
            val sp = p?.speedMbps ?: 0
            val col = when { p == null -> OFF; !up -> OFF; sp >= 1000 -> GOOD; else -> WARN }
            val short = when {
                p == null -> "?"
                !up -> "—"
                else -> p.label.substringBefore(" (")
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = rounded(if (up) CARD2 else Color.parseColor("#111827"), 16, col, if (up) 2 else 1)
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            box.addView(tv("LAN${p?.index ?: (i + 1)}", 11f, MUTED).apply { gravity = Gravity.CENTER })
            box.addView(tv(short, 22f, col, true).apply { gravity = Gravity.CENTER })
            val det = when {
                p == null -> " "
                !up -> "غير متصل"
                else -> listOfNotNull(p.speedMbps?.let { "${it}M" }, p.duplex).joinToString(" · ")
            }
            box.addView(tv(det, 10f, MUTED).apply { gravity = Gravity.CENTER; maxLines = 1 })
            portsRow.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
    }

    private fun renderDevice(r: OntReading?, p: Profile?) {
        devBox.removeAllViews()
        val rows = listOf(
            "الموديل" to r?.model,
            "الرقم التسلسلي" to r?.serial,
            "السوفتوير" to r?.software,
            "ملف الدخول" to p?.let { "${it.name} (${it.user})" },
            "العنوان" to p?.host
        )
        rows.forEachIndexed { i, (k, v) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
            row.addView(tv(k, 13f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(tv(v ?: "--", 13f, TEXT, true).apply { textDirection = View.TEXT_DIRECTION_LTR; setTextIsSelectable(true) })
            devBox.addView(row)
            if (i < rows.size - 1) devBox.addView(View(this).apply { setBackgroundColor(Color.parseColor("#22314A")) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun animateIn() {
        animCards.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dpf(24)
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 60L).setDuration(380)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun setConnected(on: Boolean, p: Profile?) {
        (statusDot.background as GradientDrawable).setColor(if (on) GOOD else OFF)
        tvStatus.text = if (on) "متصل" else "غير متصل"
        tvStatus.setTextColor(if (on) GOOD else MUTED)
        dotPulse?.cancel()
        statusDot.alpha = 1f
        if (on) {
            dotPulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.25f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
            tvSub.text = "${p?.name ?: ""} • ${p?.host ?: ""}"
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        if (b) {
            fabSpin = ObjectAnimator.ofFloat(fab, "rotation", 0f, 360f).apply {
                duration = 800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
            }
            tvUpdated.text = "جاري الاتصال والقراءة..."
        } else {
            fabSpin?.cancel()
            fab.animate().rotation(0f).setDuration(200).start()
        }
    }

    private fun showError(msg: String) {
        setConnected(false, null)
        tvError.text = msg
        errorCard.visibility = View.VISIBLE
        errorCard.alpha = 0f
        errorCard.animate().alpha(1f).setDuration(300).start()
        tvUpdated.text = "فشلت آخر محاولة"
    }

    // ---------------- الملفات (Profiles) ----------------
    private fun editProfile(index: Int) {
        val p = if (index >= 0) profiles[index] else Profile("", "192.168.", "", "")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(label: String, value: String, type: Int): EditText {
            box.addView(tv(label, 12f, MUTED), mlp(8))
            val e = EditText(this).apply {
                setText(value); inputType = type; isSingleLine = true
                setTextColor(TEXT); textDirection = View.TEXT_DIRECTION_LTR
                background = rounded(CARD2, 12, Color.parseColor("#2A3850"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            box.addView(e, mlp(4))
            return e
        }
        val eName = field("اسم الملف", p.name, InputType.TYPE_CLASS_TEXT)
        val eHost = field("عنوان IP", p.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val eUser = field("اسم المستخدم", p.user, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val ePass = field("كلمة المرور", p.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)

        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (index >= 0) "تعديل الملف" else "إضافة ملف جهاز")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val np = Profile(
                    eName.text.toString().trim().ifEmpty { eHost.text.toString().trim() },
                    eHost.text.toString().trim().removePrefix("http://").trimEnd('/'),
                    eUser.text.toString().trim(),
                    ePass.text.toString()
                )
                if (np.host.isEmpty() || np.user.isEmpty()) return@setPositiveButton
                if (index >= 0) profiles[index] = np else profiles.add(np)
                ProfileStore.save(this, profiles)
                client = null
                renderChips()
            }
            .setNegativeButton("إلغاء", null)
        if (index >= 0) b.setNeutralButton("حذف") { _, _ ->
            profiles.removeAt(index)
            ProfileStore.save(this, profiles)
            if (selected >= profiles.size || selected == index) select(-1) else renderChips()
        }
        b.show()
    }

    // ---------------- تسجيل الدخول عبر WebView ----------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun webLogin(host: String, user: String, pass: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        val base = "http://$host/"
        val js = """
            (function(){
              var u=document.getElementById('txt_Username')||document.querySelector('input[name=UserName]')||document.querySelector('input[type=text]');
              var p=document.getElementById('txt_Password')||document.querySelector('input[type=password]');
              if(!u||!p) return 'nofields';
              u.value=${jsStr(user)}; p.value=${jsStr(pass)};
              var b=document.getElementById('loginbutton')||document.getElementById('button')||document.getElementById('btnLogin')||document.querySelector('[onclick*=SubmitForm]')||document.querySelector('input[type=button],input[type=submit],button');
              if(b){b.click();return 'click';}
              if(typeof SubmitForm=='function'){SubmitForm();return 'submit';}
              return 'nobutton';
            })();
        """.trimIndent()

        ui.post {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.removeAllCookies(null)
            val wv = WebView(this)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            var submitted = false
            var tries = 0
            fun finish(r: String?) {
                if (latch.count == 0L) return
                result = r
                latch.countDown()
                ui.post { try { wv.stopLoading(); wv.destroy() } catch (e: Exception) {} }
            }
            val poll = object : Runnable {
                override fun run() {
                    if (latch.count == 0L) return
                    cm.flush()
                    val ck = cm.getCookie(base) ?: ""
                    if (ck.contains("sid=")) { finish(ck); return }
                    tries++
                    if (tries > 20) { finish(if (ck.isNotBlank()) ck else null); return }
                    ui.postDelayed(this, 1000)
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!submitted) {
                        submitted = true
                        ui.postDelayed({
                            view.evaluateJavascript(js) { }
                            ui.postDelayed(poll, 1500)
                        }, 800)
                    }
                }
            }
            wv.loadUrl(base)
        }
        latch.await(35, TimeUnit.SECONDS)
        return result
    }

    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    // ---------------- القراءة ----------------
    private fun friendly(e: Exception?, host: String?): String {
        val m = e?.message ?: return "خطأ غير معروف"
        return when {
            m.contains("timeout", true) || m.contains("failed to connect", true) || m.contains("ECONNREFUSED") || m.contains("unreachable", true) ->
                "لا يمكن الوصول إلى ${host ?: "الجهاز"}. تأكد أنك متصل بواي فاي الـ ONT."
            else -> m.substringBefore("\n\n")
        }
    }

    private fun readNow() {
        if (busy) return
        setBusy(true)
        val sel = selected
        val profs = profiles.map { it.copy() }
        val keep = autoOn

        Thread {
            try {
                Net.bindWifi(this)
                val gw = Net.gateway(this)
                val cands: List<Profile> = if (sel == -1) {
                    val hosts = profs.map { it.host }.distinct().filter { Net.reachable(it) }
                    profs.filter { it.host in hosts }.sortedBy { if (it.host == gw) 0 else 1 }
                } else listOf(profs[sel])

                if (cands.isEmpty()) {
                    val known = profs.joinToString("، ") { it.host }
                    ui.post {
                        showError("لم يتم العثور على جهاز معروف.\nعنوان الشبكة الحالية: ${gw ?: "غير معروف"}\nالعناوين المحفوظة: $known\n\nإذا كان عنوان جهازك مختلفاً أضفه من «+ إضافة».")
                    }
                    return@Thread
                }

                var lastErr: Exception? = null
                var lastHost: String? = null
                for (p in cands) {
                    val c = client?.takeIf { keep && clientProfile == p }
                        ?: HuaweiClient(p.host, p.user, p.pass) { h, u, pw -> webLogin(h, u, pw) }
                    try {
                        val r = c.read()
                        client = c
                        clientProfile = p
                        lastDump = c.dump()
                        if (!keep) c.logout()
                        ui.post { show(r, p) }
                        return@Thread
                    } catch (e: Exception) {
                        lastErr = e
                        lastHost = p.host
                        lastDump = c.dump()
                        client = null
                    }
                }
                val msg = friendly(lastErr, lastHost)
                ui.post { showError(msg) }
            } catch (e: Exception) {
                val msg = friendly(e, null)
                ui.post { showError(msg) }
            } finally {
                ui.post { setBusy(false) }
            }
        }.start()
    }

    // ---------------- التشخيص ----------------
    private fun showDump() {
        val t = TextView(this).apply {
            text = if (lastDump.isBlank()) "لا توجد بيانات بعد" else lastDump.take(60000)
            textSize = 11f
            setTextColor(TEXT)
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("البيانات الخام")
            .setView(ScrollView(this).apply { addView(t) })
            .setPositiveButton("إغلاق", null)
            .setNeutralButton("مشاركة") { _, _ -> shareDump() }
            .show()
    }

    private fun shareDump() {
        if (lastDump.isBlank()) { Toast.makeText(this, "اضغط زر التحديث أولاً", Toast.LENGTH_SHORT).show(); return }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastDump.take(100000))
        }
        startActivity(Intent.createChooser(i, "مشاركة البيانات"))
    }

    /** يحفظ التشخيص الكامل كملف .txt عبر نافذة النظام (لا يُقطع مثل نص المشاركة) */
    private fun saveDumpFile() {
        if (lastDump.isBlank()) { Toast.makeText(this, "اضغط زر التحديث أولاً", Toast.LENGTH_SHORT).show(); return }
        val stamp = SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "ont_diag_$stamp.txt")
        }
        try {
            startActivityForResult(i, REQ_SAVE)
        } catch (e: Exception) {
            shareDump()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(lastDump.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "تم حفظ ملف التشخيص ✔", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر الحفظ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(autoRunnable)
        val c = client
        Thread { try { c?.logout() } catch (e: Exception) {} }.start()
        super.onDestroy()
    }
}
