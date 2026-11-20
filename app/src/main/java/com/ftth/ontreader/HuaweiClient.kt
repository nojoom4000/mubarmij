package com.ftth.ontreader

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * الاتصال بواجهة الويب لجهاز ONT هواوي.
 * طريقة 1: تسجيل دخول مباشر (GetRandCount.asp + login.cgi بكلمة مرور Base64) — تعمل على أغلب الموديلات.
 * طريقة 2 (احتياطية): تسجيل الدخول عبر WebView مخفي باستخدام صفحة الجهاز نفسها،
 *   فتعمل حتى مع السوفتويرات التي تشفّر كلمة المرور بطريقة مختلفة.
 */
class HuaweiClient(
    var host: String,
    private val user: String,
    private val pass: String,
    /** دالة تسجيل الدخول عبر WebView: ترجع نص الكوكيز أو null */
    private val webLogin: ((host: String, user: String, pass: String) -> String?)? = null
) {
    private val cookies = LinkedHashMap<String, String>()
    val rawPages = LinkedHashMap<String, String>()
    val log = StringBuilder()
    private var loggedIn = false
    private var token: String? = null

    private val base get() = "http://$host"

    data class Resp(val code: Int, val body: String, val location: String?)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun setCookieString(s: String) {
        s.split(";").forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) cookies[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
    }

    fun request(path: String, method: String = "GET", form: String? = null): Resp {
        val url = URL(if (path.startsWith("http")) path else base + path)
        val c = url.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 7000
            c.readTimeout = 12000
            c.requestMethod = method
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            c.setRequestProperty("Referer", "$base/")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookieHeader())
            if (form != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            for ((k, list) in c.headerFields) {
                if (k != null && k.equals("Set-Cookie", true)) {
                    list.forEach { sc ->
                        val kv = sc.substringBefore(';')
                        val i = kv.indexOf('=')
                        if (i > 0) cookies[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
                    }
                }
            }
            val stream = try { if (code >= 400) c.errorStream else c.inputStream } catch (e: Exception) { null }
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(code, String(bytes, Charsets.UTF_8), c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    private fun isLoginPage(body: String): Boolean =
        body.contains("txt_Password") || body.contains("loginbutton", true) ||
            (body.contains("login.cgi") && body.contains("Password", true))

    private fun hasSession(): Boolean =
        cookies.values.any { it.contains("sid=") } || cookies.keys.any { it.equals("sid", true) || it.contains("SESSION", true) }

    // ---------------- تسجيل الدخول ----------------
    fun login(): Boolean {
        cookies.clear()
        loggedIn = false
        log.append("الاتصال بـ $base ...\n")
        val home = request("/")
        log.append("الصفحة الرئيسية: HTTP ${home.code}\n")
        rawPages["login_page"] = home.body

        // السوفتويرات الحديثة التي تستخدم تشفير SHA256/PBKDF2 → نذهب مباشرة لطريقة WebView
        val modernCrypto = Regex("(?i)pbkdf2|GetRandInfo|CryptoJS|sha256").containsMatchIn(home.body)

        if (!modernCrypto) {
            try {
                cookies["Cookie"] = "body:Language:english:id=-1"
                val tokResp = request("/asp/GetRandCount.asp", "POST", "")
                val tok = tokResp.body.replace("﻿", "").trim()
                log.append("التوكن: ${if (tok.length in 4..128) "تم" else "غير موجود"}\n")
                val pw = Base64.encodeToString(pass.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val form = "UserName=${enc(user)}&PassWord=${enc(pw)}&Language=english&x.X_HW_Token=${enc(tok)}"
                val r = request("/login.cgi", "POST", form)
                log.append("login.cgi: HTTP ${r.code}\n")
                if (hasSession() && verifySession()) {
                    loggedIn = true
                    log.append("✔ تم تسجيل الدخول (الطريقة المباشرة)\n")
                    return true
                }
                log.append("✘ الطريقة المباشرة لم تنجح\n")
            } catch (e: Exception) {
                log.append("خطأ في الطريقة المباشرة: ${e.message}\n")
            }
        } else {
            log.append("السوفتوير يستخدم تشفيراً حديثاً → تسجيل عبر WebView\n")
        }

        // الطريقة الاحتياطية
        val wl = webLogin ?: return false
        log.append("محاولة تسجيل الدخول عبر صفحة الجهاز...\n")
        val ck = wl(host, user, pass)
        if (ck.isNullOrBlank()) {
            log.append("✘ فشل تسجيل الدخول عبر WebView\n")
            return false
        }
        cookies.clear()
        setCookieString(ck)
        if (verifySession()) {
            loggedIn = true
            log.append("✔ تم تسجيل الدخول (WebView)\n")
            return true
        }
        log.append("✘ تعذر التحقق من الجلسة\n")
        return false
    }

    private fun verifySession(): Boolean {
        return try {
            val r = request("/index.asp")
            if (r.code == 200 && !isLoginPage(r.body)) {
                token = Regex("""id=["']?hwonttoken["']?\s+value=["']([^"']+)""").find(r.body)?.groupValues?.get(1)
                    ?: Regex("""hwonttoken[^>]*value=["']([^"']+)""").find(r.body)?.groupValues?.get(1)
                true
            } else false
        } catch (e: Exception) { false }
    }

    // ---------------- قراءة الصفحات ----------------
    private val pagePaths = linkedMapOf(
        "optical" to listOf(
            "/html/amp/opticinfo/opticinfo.asp",
            "/html/status/opticinfo.asp",
            "/html/amp/opticinfo/opticinfo_new.asp"
        ),
        "device" to listOf(
            "/html/ssmp/deviceinfo/deviceinfo.asp",
            "/html/status/deviceinfo.asp",
            "/html/ssmp/deviceinfo/deviceinfo_new.asp"
        ),
        "eth" to listOf(
            "/html/amp/ethinfo/ethinfo.asp",
            "/html/bbsp/ethinfo/ethinfo.asp",
            "/html/status/ethinfo.asp",
            "/html/amp/ethinfo/ethportinfo.asp"
        )
    )

    private fun fetchPages(): Int {
        var ok = 0
        var loginHits = 0
        for ((key, paths) in pagePaths) {
            for (p in paths) {
                try {
                    val r = request(p)
                    if (r.code == 200 && isLoginPage(r.body)) { loginHits++; continue }
                    if (r.code == 200 && r.body.length > 150) {
                        rawPages[key] = r.body
                        rawPages["$key:path"] = p
                        ok++
                        break
                    }
                } catch (e: Exception) {
                    log.append("خطأ في $p: ${e.message}\n")
                }
            }
        }
        if (ok == 0 && loginHits > 0) loggedIn = false
        return ok
    }

    fun read(): OntReading {
        rawPages.keys.removeAll { it != "login_page" }
        if (!loggedIn && !login()) throw Exception("فشل تسجيل الدخول. تأكد من الاتصال بواي فاي الجهاز ومن اسم المستخدم وكلمة المرور.\n\n$log")
        var n = fetchPages()
        if (n == 0) {
            // ربما انتهت الجلسة
            if (!login()) throw Exception("انتهت الجلسة وفشل إعادة الدخول.\n\n$log")
            n = fetchPages()
        }
        if (n == 0) throw Exception("تم الدخول لكن لم يتم العثور على صفحات الحالة.\n\n$log")
        val pages = rawPages.filterKeys { it in setOf("optical", "device", "eth") }
        return OntParser.parseAll(pages)
    }

    /** جلب الأجهزة المتصلة عبر GetLanUserDevInfo.asp (POST بالتوكن) */
    fun fetchDevices(): List<ClientDevice> {
        if (!loggedIn && !login()) throw Exception("فشل تسجيل الدخول.\n\n$log")
        val paths = listOf(
            "/html/bbsp/common/GetLanUserDevInfo.asp",
            "/html/amp/common/GetLanUserDevInfo.asp",
            "/html/bbsp/userdevinfo/userdevinfo.asp"
        )
        for (p in paths) {
            try {
                val t = token ?: ""
                val r = request(p, "POST", "x.X_HW_Token=${enc(t)}")
                if (r.code == 200 && !isLoginPage(r.body)) {
                    val list = OntParser.parseDevices(r.body)
                    if (list.isNotEmpty()) { rawPages["devices"] = r.body; return list }
                    if (r.body.contains("USERDevice", true) || r.body.contains("new Array", true)) return list
                }
            } catch (e: Exception) {
                log.append("devices $p: ${e.message}\n")
            }
        }
        return emptyList()
    }

    fun logout() {
        if (!loggedIn) return
        try {
            val t = token ?: ""
            request("/logout.cgi?RequestFile=html/logout.html", "POST", "x.X_HW_Token=${enc(t)}")
        } catch (_: Exception) {
        }
        loggedIn = false
        cookies.clear()
    }

    /** يقرأ توكن من صفحة معيّنة */
    private fun tokenOf(body: String): String? =
        Regex("""(?:hwonttoken|onttoken|X_HW_Token)["']?\s+value=["']([^"']+)""").find(body)?.groupValues?.get(1)
            ?: Regex("""value=["']([^"']+)["'][^>]*id=["']?hwonttoken""").find(body)?.groupValues?.get(1)

    /** إعادة تشغيل الجهاز. يرجع true عند إرسال الأمر (الاتصال سينقطع بعدها). */
    fun reboot(): Boolean {
        if (!loggedIn && !login()) throw Exception("فشل تسجيل الدخول.\n\n$log")
        // المسار الدقيق المؤكد من جهاز هواوي حقيقي (EchoLife): زر Restart يستدعي Reboot()
        // POST set.cgi?x=InternetGatewayDevice.X_HW_DEBUG.SMP.DM.ResetBoard&RequestFile=html/ssmp/reset/reset.asp
        data class R(val page: String, val cgi: String, val obj: String)
        val candidates = listOf(
            R("/html/ssmp/reset/reset.asp", "/html/ssmp/reset/set.cgi", "InternetGatewayDevice.X_HW_DEBUG.SMP.DM.ResetBoard"),
            R("/html/ssmp/reset/set.asp", "/html/ssmp/reset/set.cgi", "InternetGatewayDevice.X_HW_DEBUG.SMP.DM.ResetBoard"),
            R("/html/ssmp/reboot/reboot.asp", "/html/ssmp/reboot/set.cgi", "InternetGatewayDevice.X_HW_DEBUG.SMP.DM.ResetBoard")
        )
        for (c in candidates) {
            val g = try { request(c.page) } catch (e: Exception) { log.append("reboot: تعذر فتح ${c.page}: ${e.message}\n"); continue }
            if (g.code != 200 || isLoginPage(g.body)) continue
            val tok = tokenOf(g.body) ?: token ?: ""
            val url = "${c.cgi}?x=${enc(c.obj)}&RequestFile=${enc("html/ssmp/reset/reset.asp")}"
            log.append("إرسال إعادة التشغيل عبر ${c.cgi}\n")
            return try {
                val r = request(url, "POST", "x.X_HW_Token=${enc(tok)}")
                log.append("reboot: HTTP ${r.code}\n")
                loggedIn = false
                true
            } catch (e: Exception) {
                // قطع الاتصال بعد الإرسال مؤشر على بدء إعادة التشغيل
                log.append("reboot: انقطع الاتصال (${e.message}) — بدأت إعادة التشغيل غالباً\n")
                loggedIn = false
                true
            }
        }
        throw Exception("لم يتم العثور على صفحة إعادة التشغيل في هذا الموديل.\n\n$log")
    }

    fun dump(): String {
        val sb = StringBuilder()
        sb.append("=== LOG ===\n").append(log).append("\n")
        for ((k, v) in rawPages) {
            sb.append("\n=== $k ===\n")
            sb.append(if (v.length > 20000) v.take(20000) + "\n...[مقطوع]" else v)
            sb.append("\n")
        }
        return sb.toString()
    }
}
