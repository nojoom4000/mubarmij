package com.ftth.ontreader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var etHost: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnRead: Button
    private lateinit var swAuto: Switch
    private lateinit var tvStatus: TextView
    private lateinit var results: LinearLayout
    private var client: HuaweiClient? = null
    @Volatile private var busy = false
    private var lastDump = ""
    private val autoInterval = 15_000L

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (swAuto.isChecked) {
                readNow()
                ui.postDelayed(this, autoInterval)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(14))
            setBackgroundColor(Color.parseColor("#F2F4F7"))
        }
        val scroll = ScrollView(this).apply { addView(root); isFillViewport = true }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "قارئ ONT هواوي"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#C8102E"))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "اتصل بواي فاي الـ ONT ثم اضغط قراءة"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(10))
        })

        etHost = field(root, "عنوان الجهاز (IP)", prefs.getString("host", "192.168.100.1")!!, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        etUser = field(root, "اسم المستخدم", prefs.getString("user", "telecomadmin")!!, InputType.TYPE_CLASS_TEXT)
        etPass = field(root, "كلمة المرور", prefs.getString("pass", "admintelecom")!!, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        btnRead = Button(this).apply {
            text = "قراءة البيانات"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = round("#C8102E", 12)
            setOnClickListener { readNow() }
        }
        root.addView(btnRead, lp(dp(52)).apply { topMargin = dp(8) })

        swAuto = Switch(this).apply {
            text = "تحديث تلقائي كل 15 ثانية"
            setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                ui.removeCallbacks(autoRunnable)
                if (on) ui.post(autoRunnable)
            }
        }
        root.addView(swAuto)

        tvStatus = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(tvStatus)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "البيانات الخام"
            setOnClickListener { showDump() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "مشاركة للتشخيص"
            setOnClickListener { shareDump() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    // ---------------- أدوات الواجهة ----------------
    private fun lp(h: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)

    private fun round(color: String, r: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(2), Color.parseColor(stroke))
    }

    private fun field(parent: LinearLayout, label: String, value: String, type: Int): EditText {
        parent.addView(TextView(this).apply { text = label; setTextColor(Color.DKGRAY); textSize = 13f })
        val et = EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
            background = round("#FFFFFF", 8, "#D0D5DD")
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        parent.addView(et, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        return et
    }

    private fun card(title: String, value: String, sub: String?, color: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round("#FFFFFF", 12, color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(TextView(this).apply { text = title; setTextColor(Color.DKGRAY); textSize = 14f })
        box.addView(TextView(this).apply {
            text = value
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(color))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
        })
        if (sub != null) box.addView(TextView(this).apply { text = sub; setTextColor(Color.GRAY); textSize = 12f })
        results.addView(box, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun section(t: String) {
        results.addView(TextView(this).apply {
            text = t
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(14), 0, dp(2))
        })
    }

    private fun num(s: String?): Double? = s?.let { Regex("""-?\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

    // ---------------- تقييم القيم ----------------
    private fun rxEval(v: Double?): Pair<String, String> = when {
        v == null -> "#667085" to "لا توجد قراءة (قد يكون الفايبر غير موصول)"
        v > -8 -> "#F79009" to "قوية جداً (أعلى من ‎-8 dBm)"
        v >= -25 -> "#12B76A" to "ممتازة (بين ‎-8 و ‎-25 dBm)"
        v >= -27 -> "#F79009" to "ضعيفة (بين ‎-25 و ‎-27 dBm)"
        else -> "#D92D20" to "ضعيفة جداً (أقل من ‎-27 dBm) — افحص الكيبل والوصلات"
    }

    private fun txEval(v: Double?): Pair<String, String> = when {
        v == null -> "#667085" to "لا توجد قراءة"
        v in 0.5..5.0 -> "#12B76A" to "طبيعي (0.5 إلى 5 dBm)"
        else -> "#F79009" to "خارج المدى المعتاد (0.5 إلى 5 dBm)"
    }

    private fun tempEval(v: Double?): Pair<String, String> = when {
        v == null -> "#667085" to "لا توجد قراءة"
        v < 60 -> "#12B76A" to "طبيعية"
        v < 75 -> "#F79009" to "مرتفعة"
        else -> "#D92D20" to "مرتفعة جداً"
    }

    private fun pctEval(v: Double?): String = when {
        v == null -> "#667085"
        v < 60 -> "#12B76A"
        v < 85 -> "#F79009"
        else -> "#D92D20"
    }

    // ---------------- الشبكة ----------------
    /** يجبر التطبيق على استخدام الواي فاي حتى لو كان الواي فاي بلا إنترنت والبيانات مفعّلة */
    @Suppress("DEPRECATION")
    private fun bindToWifi() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val wifi = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            cm.bindProcessToNetwork(wifi)
        } catch (_: Exception) {
        }
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
                ui.post { try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {} }
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
    private fun readNow() {
        if (busy) return
        busy = true
        val host = etHost.text.toString().trim().removePrefix("http://").trimEnd('/')
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putString("host", host).putString("user", user).putString("pass", pass).apply()

        btnRead.isEnabled = false
        tvStatus.text = "جاري الاتصال والقراءة..."

        // في التحديث التلقائي نعيد استخدام نفس الجلسة، وفي القراءة اليدوية نبدأ جلسة جديدة
        val c = client?.takeIf { it.host == host && swAuto.isChecked } ?: HuaweiClient(host, user, pass) { h, u, p -> webLogin(h, u, p) }
        client = c

        Thread {
            bindToWifi()
            try {
                val r = c.read()
                lastDump = c.dump()
                if (!swAuto.isChecked) c.logout()
                ui.post { show(r) }
            } catch (e: Exception) {
                lastDump = c.dump()
                ui.post {
                    results.removeAllViews()
                    tvStatus.text = "✘ ${e.message ?: e.toString()}"
                }
            } finally {
                ui.post { btnRead.isEnabled = true; busy = false }
            }
        }.start()
    }

    private fun show(r: OntReading) {
        results.removeAllViews()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvStatus.text = "✔ آخر قراءة: $time"

        if (r.model != null || r.serial != null || r.software != null) {
            section("معلومات الجهاز")
            val info = listOfNotNull(
                r.model?.let { "الموديل: $it" },
                r.serial?.let { "الرقم التسلسلي: $it" },
                r.software?.let { "السوفتوير: $it" },
                r.uptime?.let { "مدة التشغيل: $it" }
            ).joinToString("\n")
            card("الجهاز", r.model ?: "-", info, "#344054")
        }

        section("الإشارة الضوئية")
        val rx = num(r.rxPower)
        val (rxC, rxT) = rxEval(rx)
        card("قوة الإشارة المستلمة (RX Power)", r.rxPower?.let { "$it dBm" } ?: "--", rxT, rxC)
        val tx = num(r.txPower)
        val (txC, txT) = txEval(tx)
        card("البور المرسل (TX Power)", r.txPower?.let { "$it dBm" } ?: "--", txT, txC)
        if (r.voltage != null || r.bias != null) {
            val vUnit = if ((num(r.voltage) ?: 0.0) > 100) "mV" else "V"
            val sub = listOfNotNull(r.voltage?.let { "الجهد: $it $vUnit" }, r.bias?.let { "تيار الليزر: $it mA" }).joinToString("   ")
            card("الموديول البصري", sub, null, "#344054")
        }

        section("حالة الجهاز")
        val t = num(r.temperature)
        val (tC, tT) = tempEval(t)
        card("درجة حرارة الجهاز", r.temperature?.let { "$it °C" } ?: "--", tT, tC)
        val cpu = num(r.cpu)
        card("استهلاك المعالج (CPU)", r.cpu?.let { if (it.contains("%")) it else "$it %" } ?: "--", null, pctEval(cpu))
        if (r.memory != null) {
            card("استهلاك الذاكرة (RAM)", if (r.memory!!.contains("%")) r.memory!! else "${r.memory} %", null, pctEval(num(r.memory)))
        }

        section("منافذ الشبكة (LAN)")
        if (r.ports.isEmpty()) {
            card("المنافذ", "--", "لم يتم العثور على معلومات المنافذ", "#667085")
        } else {
            for (p in r.ports) {
                val color = when {
                    !p.isUp -> "#98A2B3"
                    (p.speedMbps ?: 0) >= 1000 -> "#12B76A"
                    else -> "#F79009"
                }
                val sub = listOfNotNull(
                    p.status?.let { "الحالة: $it" },
                    p.duplex?.let { "Duplex: $it" },
                    p.speed?.let { "السرعة: $it" }
                ).joinToString("   ")
                card("منفذ LAN ${p.index}", p.label, sub, color)
            }
        }
    }

    private fun showDump() {
        val tv = TextView(this).apply {
            text = if (lastDump.isBlank()) "لا توجد بيانات بعد" else lastDump.take(60000)
            textSize = 11f
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("البيانات الخام")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun shareDump() {
        if (lastDump.isBlank()) { Toast.makeText(this, "اضغط قراءة أولاً", Toast.LENGTH_SHORT).show(); return }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastDump.take(100000))
        }
        startActivity(Intent.createChooser(i, "مشاركة البيانات"))
    }

    override fun onDestroy() {
        ui.removeCallbacks(autoRunnable)
        val c = client
        Thread { try { c?.logout() } catch (_: Exception) {} }.start()
        super.onDestroy()
    }
}
