package com.ftth.ontreader

import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * قياس سرعة الإنترنت عبر خوادم Cloudflare المجانية.
 *   التحميل: GET https://speed.cloudflare.com/__down?bytes=N
 *   الرفع:   POST https://speed.cloudflare.com/__up (جسم بحجم N)
 *   البنغ:   زمن طلب صغير
 * القياس يمر عبر شبكة الواي فاي التي رُبط بها التطبيق (خط الزبون).
 */
class SpeedTest(
    private val onProgress: (phase: String, mbps: Double, pct: Int) -> Unit
) {
    @Volatile var cancelled = false

    data class Result(val pingMs: Double, val jitterMs: Double, val downMbps: Double, val upMbps: Double)

    private val DOWN = "https://speed.cloudflare.com/__down?bytes="
    private val UP = "https://speed.cloudflare.com/__up"

    fun run(): Result {
        val (ping, jitter) = measurePing()
        val down = measureDownload()
        val up = measureUpload()
        return Result(ping, jitter, down, up)
    }

    // ---------------- Ping ----------------
    private fun measurePing(): Pair<Double, Double> {
        val samples = ArrayList<Double>()
        repeat(6) {
            if (cancelled) return@repeat
            try {
                val t0 = System.nanoTime()
                val c = URL(DOWN + "0").openConnection() as HttpsURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.requestMethod = "GET"
                c.inputStream.use { it.readBytes() }
                c.disconnect()
                val ms = (System.nanoTime() - t0) / 1e6
                samples.add(ms)
                onProgress("ping", 0.0, (samples.size * 100 / 6))
            } catch (e: Exception) {
            }
        }
        if (samples.isEmpty()) return 0.0 to 0.0
        val sorted = samples.sorted()
        val min = sorted.first()
        val jitter = if (sorted.size > 1) sorted.zipWithNext { a, b -> Math.abs(b - a) }.average() else 0.0
        return min to jitter
    }

    // ---------------- التحميل ----------------
    private fun measureDownload(): Double {
        // عدة أحجام متصاعدة خلال ~10 ثوانٍ، نأخذ أفضل معدل مستقر
        val sizes = longArrayOf(10_000_000, 25_000_000, 50_000_000, 100_000_000)
        var best = 0.0
        val start = System.currentTimeMillis()
        for (sz in sizes) {
            if (cancelled || System.currentTimeMillis() - start > 12_000) break
            try {
                val c = URL(DOWN + sz).openConnection() as HttpsURLConnection
                c.connectTimeout = 7000; c.readTimeout = 20000
                c.requestMethod = "GET"
                val t0 = System.nanoTime()
                var total = 0L
                c.inputStream.use { ins ->
                    val buf = ByteArray(65536)
                    while (true) {
                        if (cancelled) break
                        val n = ins.read(buf); if (n < 0) break
                        total += n
                        val sec = (System.nanoTime() - t0) / 1e9
                        if (sec > 0.3) {
                            val mbps = total * 8.0 / sec / 1e6
                            if (mbps > best) best = mbps
                            onProgress("download", mbps, Math.min(99, (sec / 8.0 * 100).toInt()))
                        }
                        if (sec > 8) break
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
            }
        }
        onProgress("download", best, 100)
        return best
    }

    // ---------------- الرفع ----------------
    private fun measureUpload(): Double {
        var best = 0.0
        val chunk = ByteArray(1_000_000) // 1MB عشوائي يُعاد إرساله
        val start = System.currentTimeMillis()
        val rounds = 6
        for (i in 0 until rounds) {
            if (cancelled || System.currentTimeMillis() - start > 12_000) break
            try {
                val c = URL(UP).openConnection() as HttpsURLConnection
                c.connectTimeout = 7000; c.readTimeout = 20000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/octet-stream")
                val sendMb = 8
                c.setFixedLengthStreamingMode(chunk.size.toLong() * sendMb)
                val t0 = System.nanoTime()
                var total = 0L
                c.outputStream.use { os ->
                    repeat(sendMb) {
                        if (cancelled) return@repeat
                        os.write(chunk); total += chunk.size
                        os.flush()
                        val sec = (System.nanoTime() - t0) / 1e9
                        if (sec > 0.3) {
                            val mbps = total * 8.0 / sec / 1e6
                            if (mbps > best) best = mbps
                            onProgress("upload", mbps, Math.min(99, ((i + 1) * 100 / rounds)))
                        }
                    }
                }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) {
            }
        }
        onProgress("upload", best, 100)
        return best
    }
}
