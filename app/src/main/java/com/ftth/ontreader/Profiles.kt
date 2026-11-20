package com.ftth.ontreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/** ملف دخول لنوع من أجهزة ONT */
data class Profile(var name: String, var host: String, var user: String, var pass: String)

object ProfileStore {
    val defaults = listOf(
        Profile("هواوي 100.1", "192.168.100.1", "telecomadmin", "admintelecom"),
        Profile("EP 18.1", "192.168.18.1", "Epadmin", "adminEp")
    )

    fun load(ctx: Context): MutableList<Profile> {
        val js = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("profiles", null)
            ?: return defaults.map { it.copy() }.toMutableList()
        return try {
            val arr = JSONArray(js)
            val out = ArrayList<Profile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Profile(o.optString("name"), o.optString("host"), o.optString("user"), o.optString("pass")))
            }
            out
        } catch (e: Exception) {
            defaults.map { it.copy() }.toMutableList()
        }
    }

    fun save(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("host", it.host).put("user", it.user).put("pass", it.pass))
        }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("profiles", arr.toString()).apply()
    }
}

object Net {
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        if (Build.VERSION.SDK_INT < 21) return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** يجبر التطبيق على استخدام الواي فاي حتى لو كان بلا إنترنت والبيانات مفعّلة */
    fun bindWifi(ctx: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(wifiNetwork(ctx))
        } catch (e: Exception) {
        }
    }

    /** عنوان البوابة (الراوتر/ONT) لشبكة الواي فاي الحالية */
    @Suppress("DEPRECATION")
    fun gateway(ctx: Context): String? {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = wifiNetwork(ctx)
            if (net != null) {
                val gw = cm.getLinkProperties(net)?.routes
                    ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
                    ?.gateway?.hostAddress
                if (!gw.isNullOrBlank() && gw != "0.0.0.0") return gw
            }
        } catch (e: Exception) {
        }
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val g = wm.dhcpInfo?.gateway ?: 0
            if (g == 0) null
            else "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"
        } catch (e: Exception) {
            null
        }
    }

    fun reachable(host: String, timeoutMs: Int = 1500): Boolean {
        val h = host.substringBefore(':')
        val port = host.substringAfter(':', "80").toIntOrNull() ?: 80
        return try {
            Socket().use { it.connect(InetSocketAddress(h, port), timeoutMs) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
