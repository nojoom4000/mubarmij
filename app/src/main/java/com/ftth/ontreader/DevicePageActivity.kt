package com.ftth.ontreader

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * تعرض صفحة إعدادات حقيقية من الجهاز (WAN أو WiFi) داخل التطبيق، مع تسجيل دخول تلقائي.
 * التعديل والحفظ يتمان بواجهة الجهاز نفسها — أضمن وأأمن من إعادة بناء الطلب يدوياً.
 */
class DevicePageActivity : Activity() {

    private val BG = Color.parseColor("#0B1220")
    private val ACCENT = Color.parseColor("#38BDF8")

    private lateinit var web: WebView
    private var navigated = false
    private var loginTries = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG

        val host = intent.getStringExtra("host") ?: "192.168.100.1"
        val user = intent.getStringExtra("user") ?: "telecomadmin"
        val pass = intent.getStringExtra("pass") ?: "admintelecom"
        val target = intent.getStringExtra("target") ?: "/"
        val title = intent.getStringExtra("title") ?: "إعدادات الجهاز"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        // شريط علوي
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#141D2E")); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = "‹ رجوع"; setTextColor(ACCENT); textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "تحديث"; setTextColor(ACCENT); textSize = 14f
            setOnClickListener { navigated = false; loginTries = 0; web.loadUrl("http://$host/") }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "هذه صفحة الجهاز الأصلية. عدّل ما تريد ثم اضغط Apply/حفظ في الصفحة. أي تغيير في WAN قد يقطع خدمة الزبون."
            setTextColor(Color.parseColor("#F59E0B")); textSize = 12f
            setBackgroundColor(Color.parseColor("#1B263A")); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val frame = FrameLayout(this)
        web = WebView(this)
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        val loginJs = """
            (function(){
              var u=document.getElementById('txt_Username')||document.querySelector('input[name=UserName]')||document.querySelector('input[type=text]');
              var p=document.getElementById('txt_Password')||document.querySelector('input[type=password]');
              if(!u||!p) return 'nologin';
              u.value=${jsStr(user)}; p.value=${jsStr(pass)};
              if(typeof SubmitForm=='function'){SubmitForm();return 'submit';}
              var b=document.getElementById('loginbutton')||document.getElementById('button')||document.getElementById('btnLogin')||document.getElementById('loginBtn')||document.querySelector('input[type=button],input[type=submit],button');
              if(!b){var all=[...document.querySelectorAll('button,input[type=button],input[type=submit],a,div,span')];b=all.find(function(x){return /log\s*in|login|دخول|تسجيل/i.test((x.textContent||x.value||''))&&(x.offsetWidth||x.offsetHeight);});}
              if(b){b.click();return 'click';}
              return 'nobtn';
            })();
        """.trimIndent()

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                view.evaluateJavascript("(document.getElementById('txt_Password')?1:0)") { r ->
                    val isLogin = r.trim('"') == "1"
                    if (isLogin && loginTries < 3) {
                        loginTries++
                        view.evaluateJavascript(loginJs) {}
                    } else if (!navigated && !isLogin) {
                        navigated = true
                        Handler(Looper.getMainLooper()).postDelayed({
                            view.loadUrl("http://$host" + target)
                        }, 400)
                    }
                }
            }
        }
        web.loadUrl("http://$host/")
    }

    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
