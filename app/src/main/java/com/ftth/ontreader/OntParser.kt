package com.ftth.ontreader

/**
 * محلل صفحات واجهة ONT هواوي.
 * صفحات هواوي تضع البيانات داخل JavaScript بالشكل:
 *   function stOpticInfo(domain, transOpticPower, revOpticPower, ...) {...}
 *   var opticInfos = new Array(new stOpticInfo("...","2.10","-19.50",...), null);
 * لذلك نقرأ أسماء المعاملات من تعريف الدالة ونربطها بالقيم، فيعمل مع اختلاف الترتيب بين الموديلات.
 */

data class JsObj(val type: String, val fields: LinkedHashMap<String, String>)

data class PortInfo(
    val index: Int,
    val status: String?,
    val speed: String?,
    val duplex: String?,
    val type: String?
) {
    val isUp: Boolean
        get() {
            val s = status?.lowercase() ?: ""
            if (s.contains("nolink") || s.contains("down") || s == "0" || s.contains("disconnect")) return false
            if (s.contains("up") || s == "1" || s.contains("connect")) return true
            val sp = speedMbps
            return sp != null && sp > 0
        }

    val speedMbps: Int?
        get() {
            val raw = speed?.trim() ?: return null
            // قيمة بالـ bps (رقم كبير) → Mbps
            Regex("""^\d{7,}$""").find(raw)?.let { return (it.value.toLong() / 1_000_000L).toInt() }
            // رقم متبوع بوحدة G أو M
            Regex("""(?i)(\d+(?:\.\d+)?)\s*(g|m)""").find(raw)?.let { m ->
                val n = m.groupValues[1].toDouble()
                return if (m.groupValues[2].equals("g", true)) (n * 1000).toInt() else n.toInt()
            }
            val n = raw.toIntOrNull() ?: Regex("""\d+""").find(raw)?.value?.toIntOrNull() ?: return null
            // رقم ≥ 10 يعني أنها Mbps فعلية (10/100/1000/2500...)
            // رقم 0..9 يعني رمز سرعة من هواوي: 0=10، 1=100، 2=1000، 3=2500، 4=10000
            return if (n >= 10) n else intArrayOf(10, 100, 1000, 2500, 10000).getOrElse(n) { n }
        }

    /** GE / FE / ... */
    val label: String
        get() {
            val t = type?.uppercase()
            if (!isUp) return "غير متصل"
            val sp = speedMbps
            return when {
                sp == null && t != null && (t.contains("GE") || t.contains("FE")) -> t
                sp == null -> "غير معروف"
                sp >= 10000 -> "10GE (10G)"
                sp >= 2500 -> "2.5GE (2.5G)"
                sp >= 1000 -> "GE (1000M)"
                sp >= 100 -> "FE (100M)"
                else -> "${sp}M"
            }
        }
}

data class OntReading(
    var rxPower: String? = null,
    var txPower: String? = null,
    var temperature: String? = null,
    var voltage: String? = null,
    var bias: String? = null,
    var cpu: String? = null,
    var memory: String? = null,
    var model: String? = null,
    var serial: String? = null,
    var software: String? = null,
    var uptime: String? = null,
    var ports: List<PortInfo> = emptyList()
)

object OntParser {

    private val NUM = Regex("""^\s*[-+]?\d+(\.\d+)?\s*$""")

    // ---------------- فك ترميز JavaScript ----------------
    fun decodeJs(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when {
                    n == 'x' && i + 3 < s.length -> {
                        val h = s.substring(i + 2, i + 4).toIntOrNull(16)
                        if (h != null) { sb.append(h.toChar()); i += 4; continue }
                    }
                    n == 'u' && i + 5 < s.length -> {
                        val h = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (h != null) { sb.append(h.toChar()); i += 6; continue }
                    }
                    n == 'n' -> { sb.append('\n'); i += 2; continue }
                    n == 't' -> { sb.append('\t'); i += 2; continue }
                    else -> { sb.append(n); i += 2; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    // ---------------- قراءة معاملات الاستدعاء ----------------
    /** يقرأ المعاملات بدءاً من الموضع بعد '(' ويرجع القيم وموضع النهاية */
    fun parseArgs(s: String, start: Int): Pair<List<String>, Int> {
        val out = ArrayList<String>()
        var i = start
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            if (s[i] == ')') return out to i + 1
            val c = s[i]
            if (c == '"' || c == '\'') {
                val sb = StringBuilder()
                i++
                while (i < s.length && s[i] != c) {
                    if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i]).append(s[i + 1]); i += 2; continue }
                    sb.append(s[i]); i++
                }
                i++ // إغلاق النص
                // ربط النصوص "a" + "b"
                out.add(decodeJs(sb.toString()))
                while (i < s.length && s[i] != ',' && s[i] != ')') i++
            } else {
                val sb = StringBuilder()
                var depth = 0
                while (i < s.length) {
                    val ch = s[i]
                    if (ch == '(' || ch == '[' || ch == '{') depth++
                    if (ch == ')' || ch == ']' || ch == '}') { if (depth == 0) break; depth-- }
                    if (ch == ',' && depth == 0) break
                    sb.append(ch); i++
                }
                out.add(sb.toString().trim())
            }
            if (i < s.length && s[i] == ',') i++
            if (out.size > 300) break
        }
        return out to i
    }

    // ---------------- استخراج الكائنات ----------------
    fun extractObjects(html: String): List<JsObj> {
        val defs = HashMap<String, List<String>>()
        Regex("""function\s+(\w+)\s*\(([^)]*)\)""").findAll(html).forEach { m ->
            val params = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (params.size >= 2) defs[m.groupValues[1]] = params
        }
        val objs = ArrayList<JsObj>()
        Regex("""new\s+(\w+)\s*\(""").findAll(html).forEach { m ->
            val type = m.groupValues[1]
            if (type == "Array" || type == "Date" || type == "RegExp" || type == "Object") return@forEach
            val (args, _) = parseArgs(html, m.range.last + 1)
            if (args.isEmpty()) return@forEach
            val names = defs[type]
            val map = LinkedHashMap<String, String>()
            args.forEachIndexed { idx, v ->
                val key = names?.getOrNull(idx) ?: "p$idx"
                map[key] = v
            }
            objs.add(JsObj(type, map))
        }
        return objs
    }

    private fun clean(v: String?): String? {
        if (v == null) return null
        val t = v.trim().trim('"', '\'').trim()
        if (t.isEmpty() || t == "null" || t == "undefined") return null
        return t
    }

    private fun findField(objs: List<JsObj>, name: Regex, numeric: Boolean = true, exclude: Regex? = null): String? {
        for (o in objs) for ((k, v) in o.fields) {
            if (name.containsMatchIn(k) && (exclude == null || !exclude.containsMatchIn(k))) {
                val c = clean(v) ?: continue
                if (!numeric || NUM.matches(c) || c.contains("--")) return c
                if (numeric && Regex("""-?\d+(\.\d+)?""").containsMatchIn(c)) return c
            }
        }
        return null
    }

    /** بحث نصي احتياطي: var xxx = '12'  أو  xxx: "12" */
    private fun findVar(html: String, namePattern: String): String? {
        val r = Regex("""(?i)\b\w*(?:$namePattern)\w*\s*[=:]\s*['"]?\s*(-?\d+(?:\.\d+)?)""")
        return r.find(html)?.groupValues?.get(1)
    }

    /** بحث احتياطي من العناوين المعروضة في الصفحة */
    private fun findByLabel(html: String, label: String): String? {
        val r = Regex("""(?is)$label.{0,300}?(-?\d+(?:\.\d+)?)""")
        return r.find(html)?.groupValues?.get(1)
    }

    // ---------------- الصفحة الضوئية ----------------
    fun parseOptical(html: String, r: OntReading) {
        val objs = extractObjects(html)
        r.rxPower = r.rxPower
            ?: findField(objs, Regex("(?i)(rev|rx|recv|receiv).*(power|optic)|^rxpower$|rxopticalpower"))
            ?: findVar(html, "rev\\w*power|rx\\w*power|RxOptical")
            ?: findByLabel(html, "(?:Rx|Receive[d]?)\\s*Optical\\s*Power")
        r.txPower = r.txPower
            ?: findField(objs, Regex("(?i)(trans|tx|send).*(power|optic)|^txpower$"))
            ?: findVar(html, "trans\\w*power|tx\\w*power|TxOptical")
            ?: findByLabel(html, "(?:Tx|Transmit(?:ted)?)\\s*Optical\\s*Power")
        r.temperature = r.temperature
            ?: findField(objs, Regex("(?i)temp"))
            ?: findVar(html, "temperature|temprature|temper")
            ?: findByLabel(html, "(?:Working|Optical\\s*Module)?\\s*Temperature")
        r.voltage = r.voltage
            ?: findField(objs, Regex("(?i)volt"))
            ?: findVar(html, "voltage")
        r.bias = r.bias
            ?: findField(objs, Regex("(?i)bias|current"))
            ?: findVar(html, "bias")

        // احتياطي لـ TX: داخل كائن الإشارة، اختر حقل قدرة بالـ dBm غير RX
        if (r.txPower == null) {
            val rxVal = r.rxPower
            outer@ for (o in objs) {
                if (o.fields.keys.none { Regex("(?i)optic|power|temp|volt|bias|signal|level").containsMatchIn(it) }) continue
                for ((k, v) in o.fields) {
                    if (Regex("(?i)rx|rev|recv|receiv|temp|volt|bias|current|domain|vendor|status|wave|dist|serial|mode").containsMatchIn(k)) continue
                    if (!Regex("(?i)power|optic|level|tx|trans|send|launch|signal").containsMatchIn(k)) continue
                    val c = clean(v) ?: continue
                    if (c == rxVal) continue
                    val d = c.toDoubleOrNull() ?: continue
                    if (d in -50.0..20.0) { r.txPower = c; break@outer }
                }
            }
        }
    }

    // ---------------- صفحة معلومات الجهاز ----------------
    fun parseDevice(html: String, r: OntReading) {
        val objs = extractObjects(html)
        r.cpu = r.cpu
            ?: findField(objs, Regex("(?i)cpu"))
            ?: findVar(html, "cpu")
            ?: findByLabel(html, "CPU\\s*(?:Usage|Usage\\s*Rate|Used|Occupancy)")
        r.memory = r.memory
            ?: findField(objs, Regex("(?i)mem"))
            ?: findVar(html, "mem\\w*(?:used|usage|rate|occup)")
            ?: findByLabel(html, "Memory\\s*(?:Usage|Usage\\s*Rate|Used|Occupancy)")
        r.model = r.model ?: findField(objs, Regex("(?i)modelname|devtype|model|productclass"), numeric = false)
        r.serial = r.serial ?: findField(objs, Regex("(?i)serial|^sn$"), numeric = false)
        r.software = r.software ?: findField(objs, Regex("(?i)softwarever|swver|software"), numeric = false)
        r.uptime = r.uptime ?: findField(objs, Regex("(?i)uptime|runtime|runningtime"), numeric = false)
            ?: findVar(html, "uptime")
        if (r.temperature == null) {
            r.temperature = findField(objs, Regex("(?i)temp")) ?: findVar(html, "temperature|temprature")
        }
    }

    // ---------------- صفحة المنافذ ----------------
    private val SPEED_VAL = Regex("""(?i)^(10|100|1000|2500|10000)\s*(m|mbps|mbit/s)?$|^(1|2\.5|10)\s*g(bps)?$""")
    private val STATUS_VAL = Regex("""(?i)^(up|down|nolink|linkup|linkdown|connected|disconnected|enable|disable)$""")
    private val DUPLEX_VAL = Regex("""(?i)^(full|half|fullduplex|halfduplex|auto)$""")

    fun parsePorts(html: String, r: OntReading) {
        val objs = extractObjects(html)
        val ports = ArrayList<PortInfo>()
        for (o in objs) {
            var speed: String? = null
            var status: String? = null
            var duplex: String? = null
            var type: String? = null
            for ((k, v0) in o.fields) {
                val v = clean(v0) ?: continue
                val kl = k.lowercase()
                if (speed == null && (kl.contains("speed") || kl.contains("bitrate") || kl == "rate") && !kl.contains("max")) speed = v
                else if (status == null && (kl.contains("status") || kl.contains("link")) && !kl.contains("speed")) status = v
                else if (duplex == null && kl.contains("duplex")) duplex = v
                else if (type == null && (kl.contains("porttype") || kl == "type") && Regex("(?i)GE|FE").containsMatchIn(v)) type = v
            }
            // عند غياب أسماء المعاملات نعتمد على شكل القيم
            val vals = o.fields.values.mapNotNull { clean(it) }
            if (speed == null) speed = vals.firstOrNull { SPEED_VAL.matches(it) }
            if (status == null) status = vals.firstOrNull { STATUS_VAL.matches(it) }
            if (duplex == null) duplex = vals.firstOrNull { DUPLEX_VAL.matches(it) }

            val looksLikePort = (speed != null && (status != null || duplex != null)) ||
                    (status != null && duplex != null) ||
                    o.fields.values.any { it.contains("LANEthernetInterfaceConfig", true) || it.contains("EthernetInterface", true) }
            val looksLikeStats = o.fields.keys.any { Regex("(?i)packet|bytes|frame|err").containsMatchIn(it) } && speed == null
            if (looksLikePort && !looksLikeStats) {
                ports.add(PortInfo(ports.size + 1, status, speed, duplex, type))
            }
        }
        if (ports.isNotEmpty()) r.ports = ports
    }

    fun parseAll(pages: Map<String, String>): OntReading {
        val r = OntReading()
        pages["optical"]?.let { parseOptical(it, r) }
        pages["device"]?.let { parseDevice(it, r) }
        pages["eth"]?.let { parsePorts(it, r) }
        // آخر محاولة: البحث في كل الصفحات
        val all = pages.values.joinToString("\n")
        if (r.rxPower == null || r.txPower == null || r.temperature == null) parseOptical(all, r)
        if (r.cpu == null || r.memory == null) parseDevice(all, r)
        return r
    }
}
