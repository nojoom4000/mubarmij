package com.ftth.ontreader

import java.net.HttpURLConnection
import java.net.URL

/**
 * تحديد الشركة المصنّعة لجهاز من عنوان MAC عبر الـ OUI (أول 3 بايت).
 * - قائمة مضمّنة لأشهر فندرات الراوترات/الأجهزة (تعمل بدون إنترنت).
 * - بحث أونلاين احتياطي (api.macvendors.com) يغطّي بقية الفندرات عند توفر إنترنت، مع تخزين مؤقت.
 */
object VendorDB {
    private val cache = HashMap<String, String>()

    // OUI (6 hex بلا فواصل، أحرف كبيرة) → اسم الشركة. عدة بادئات لكل فندر شائع.
    private val map: Map<String, String> = buildMap {
        fun v(vendor: String, vararg ouis: String) {
            ouis.forEach { raw ->
                val k = raw.filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }.uppercase()
                if (k.length >= 6) put(k.substring(0, 6), vendor)
            }
        }
        v("TP-Link", "001478","0C8268","14CC20","50C7BF","54C80F","60E327","6466B3","788A20","8C210A","90F652","A42BB0","AC84C6","B0487A","C006C3","C46E1F","D84732","E894F6","EC086B","F0A731","F4F26D","F8D111","1C3BF3","3C52A1","9C5322","B4B024","003192")
        v("Mercusys", "28FF3C","5C628B","C44619")
        v("Huawei", "00E0FC","0025­9E","283CE4","48435A","4C5499","5CA86A","70723C","84A8E4","9CB2B2","ACE215","B41513","D0D04B","E0247F","F4C714","00259E","781DBA","283152","C8D15E","2008ED","00664B","78F557","DC21A1","30DE4B","645601","20F3A3","10C61F","04C06F")
        v("ZTE", "0015EB","0019C6","001E73","344B50","4C09B4","68922D","9C6F52","D0608C","EC8A4C","F46DE2","346987","B075D5","CC7B35")
        v("Tenda", "0005E4","08574E","50FA84","84BA3B","C83A35","D8EB97","FCEC30","AC57B0","388345","04BAD6")
        v("D-Link", "001346","0015E9","001B11","0022B0","0024A5","1CBDB9","284887","340804","7898E8","B8A386","C8BE19","CCB255","FC7516")
        v("MikroTik", "0C5B8F","18FD74","2CC81B","48A98A","4C5E0C","64D154","6C3B6B","744D28","B869F4","CC2DE0","DC2C6E","E48D8C","08552EA","78DF72")
        v("Cisco / Linksys", "00000C","000142","0016B6","001A70","002369","68EFBD","C06218","C8D719","E8ED05","000C41","0018F8","48F8B3","58BF20")
        v("Netgear", "000FB5","0014 6C","00223F","00265A","20E52A","288088","2C3033","6CB0CE","9C3DCF","A040A0","C03F0E","E091F5","204E7F","008EF2")
        v("Asus", "001BFC","00223F","08606E","107B44","2C56DC","305A3A","382C4A","50465D","704D7B","AC220B","BCEE7B","D017C2","F832E4","1C872C")
        v("Xiaomi", "009EC8","0C1DAF","103047","286C07","3480B3","50647B","642737","7802F8","8CBEBE","A063F5","F0B429","FC64BA","64CC2E","B0E235")
        v("Zyxel", "0013 49","001349","002098","40A5EF","5C6A80","901436","A021B7","B0B2DC","D0E540","E4BE13")
        v("Ubiquiti", "0027 22","002722","04184E","24A43C","44D9E7","68D79A","74ACB9","78 8A20","802AA8","B4FBE4","DC9FDB","F09FC2","FCECDA")
        v("Totolink", "003192","2C957F","70F1A1")
        v("Fiberhome", "001E59","70FD45","C83A6B","E0C255")
        v("Nokia / Alcatel", "001761","00229B","1CB796","40B4CD","5CBD9E","8C90D3","BC9911","E86F38")
        v("Sagemcom", "001D19","0026 F2","4C60DE","6CB749","849CA6","C8B422","F8D111")
        v("Technicolor", "001092","08362A","1C9ECC","3C8375","7CB542","A8D3F7","DC537C","E838DF")
        v("Arcadyan", "0012BF","00249B","1C98EC","6C7384","8CFDF0","A0648F","E0CB1D")
        v("AVM (FRITZ!Box)", "00040E","001F3F","08963B","244976","3810D5","9CC7A6","C0C9E3","E80410")
        v("Realtek", "00E04C","525400","001337")
        v("Ruijie", "00749C","1414 4B","5869 6C","8005 88","EC B9 70")
        v("Apple", "000A27","001124","0017F2","3C0754","40331A","68AB1E","7CD1C3","A85C2C","BC926B","F0DBF8","F86214","DCA4CA")
        v("Samsung", "0000F0","0012FB","0021D1","1C5A3E","34BE00","5CF6DC","78471D","8C7712","A00BBA","C8BA94","E8508B","F409D8")
        v("Intel", "001B21","3C9772","7CB27D","A0A8CD","E4A471","F8E4FB")
        v("Sony", "00041F","001A80","FCF152")
        v("LG", "001E75","10683F","344DF7","A816B2","C4438F")
        v("Dell", "00065B","001422","B8CA3A","F8BC12")
        v("Amazon", "0C47C9","34D270","6837E9","AC63BE","F0F0A4")
        v("Honor", "F4E4AD","C8F230","34CDBE")
        v("Oppo/Realme", "00249F","5CC6D0","D4769A")
        v("Vivo", "0C8910","2CBABA","8C79F5")
        v("Google", "3C5AB4","54605F","F4F5D8","A4DA32")
        v("Microsoft", "0017FA","2816A8","60455E","C83F26")
    }

    fun oui(mac: String?): String? {
        if (mac == null) return null
        val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length < 6) return null
        return hex.substring(0, 6)
    }

    /** بحث فوري من القائمة المضمّنة أو الذاكرة المؤقتة (بدون شبكة) */
    fun cached(mac: String?): String? {
        val o = oui(mac) ?: return null
        return map[o] ?: cache[o]
    }

    /** بحث كامل: المضمّنة ثم أونلاين (يُستدعى من خيط عامل فقط) */
    fun resolve(mac: String?): String? {
        val m = mac ?: return null
        val o = oui(m) ?: return null
        map[o]?.let { return it }
        cache[o]?.let { return it }
        val online = lookupOnline(m) ?: return null
        cache[o] = online
        return online
    }

    private fun lookupOnline(mac: String): String? {
        return try {
            val c = URL("https://api.macvendors.com/${mac.filter { it.isLetterOrDigit() || it == ':' }}").openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 4000
            c.requestMethod = "GET"
            if (c.responseCode == 200) {
                val s = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                if (s.isNotBlank() && !s.startsWith("{") && s.length < 80) s else null
            } else { c.disconnect(); null }
        } catch (e: Exception) { null }
    }
}
