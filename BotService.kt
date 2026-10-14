package com.example.mubot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MU Immortal - Raklion boss kontrol botu.
 *
 * Mantik:
 *  - Her kanalda (1,2,3) haritayi acar, sol listeden Raklion 1/2/3'u sirayla secer.
 *  - Ghost Aragog ismi KIRMIZI ise canli, GRI ise olu demektir.
 *  - Canliysa boss'a dokunur (oyun yuruturu), sonra Manual dugmesine basip oyunun
 *    kendi otomatik saldirisini acar. Boss olunce otomatigi kapatir, siradaki kanala gecer.
 */
class BotService : AccessibilityService() {

    private class StopEx : RuntimeException()

    companion object {
        const val ICON_DY = -0.038f      // isim etiketinden ikon merkezine dikey fark
        const val RED_THR = 0.025f       // etiket bolgesinde kirmizi piksel orani esigi
        const val LOAD_SEC = 5           // kanal degisince yukleme bekleme
        const val RECHECK_SEC = 8        // savas sirasinda boss durumunu kontrol araligi
        const val FIGHT_TIMEOUT_SEC = 600

        val NAMES = listOf(
            "minimap", "wire", "ch1", "ch2", "ch3", "switchLine", "closeChannel", "closeMap",
            "mapScroll", "raklionParent", "raklion1Child", "raklion2Child",
            "targetR2", "targetR1A", "targetR1B", "boss1", "boss2", "buffPoint", "manual"
        )

        val LABELS = mapOf(
            "minimap" to "Sag ustteki mini harita (haritayi acar)",
            "wire" to "Wire butonu (kanal ekranini acar)",
            "ch1" to "Kanal ekraninda 1. kanal",
            "ch2" to "Kanal ekraninda 2. kanal",
            "ch3" to "Kanal ekraninda 3. kanal",
            "switchLine" to "Switch Line butonu",
            "closeChannel" to "Kanal ekrani X (kapat) dugmesi",
            "closeMap" to "Harita ekrani X (kapat) dugmesi",
            "mapScroll" to "Harita listesinde asagi kaydirma noktasi",
            "raklionParent" to "Harita listesindeki Raklion ana satiri (ac/kapat)",
            "raklion1Child" to "Raklion acildiktan sonra Raklion 1 alt satiri",
            "raklion2Child" to "Raklion acildiktan sonra Raklion 2 alt satiri",
            "targetR2" to "Raklion 2 boss harita simgesi (yol hedefi)",
            "targetR1A" to "Raklion 1 birinci boss harita simgesi (yol hedefi)",
            "targetR1B" to "Raklion 1 ikinci boss harita simgesi (yol hedefi)",
            "boss1" to "Raklion 1 savas ekraninda boss ISIM etiketi (can kontrolu)",
            "boss2" to "Raklion 2 savas ekraninda boss ISIM etiketi (can kontrolu)",
            "buffPoint" to "Raklion 2 haritasinda buff konumu (yol hedefi)",
            "manual" to "Manual (oto saldiri) dugmesi"
        )

        // Ekran goruntulerinden (2419x1512) hesaplanan oranlar
        val DEFAULTS = mapOf(
            "minimap" to Pair(0.922f, 0.046f),
            "wire" to Pair(0.992f, 0.050f),
            "ch1" to Pair(0.498f, 0.326f),
            "ch2" to Pair(0.498f, 0.456f),
            "ch3" to Pair(0.498f, 0.581f),
            "switchLine" to Pair(0.499f, 0.739f),
            "closeChannel" to Pair(0.637f, 0.202f),
            "closeMap" to Pair(0.894f, 0.157f),
            "mapScroll" to Pair(0.220f, 0.780f),
            "raklionParent" to Pair(0.220f, 0.390f),
            "raklion1Child" to Pair(0.240f, 0.430f),
            "raklion2Child" to Pair(0.240f, 0.470f),
            "targetR2" to Pair(0.612f, 0.640f),
            "targetR1A" to Pair(0.469f, 0.494f),
            "targetR1B" to Pair(0.469f, 0.494f),
            "boss1" to Pair(0.469f, 0.494f),
            "boss2" to Pair(0.612f, 0.640f),
            "buffPoint" to Pair(0.500f, 0.500f),
            "manual" to Pair(0.972f, 0.473f)
        )

        val WALK_OPTIONS = listOf(10, 15, 20, 30, 45)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var extra: View? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var lineBtn: Button? = null
    private var walkBtn: Button? = null

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private var startLine = 1
    private var walkIdx = 1
    private var sent = 0
    private var cancelled = 0
    private var lastShot = 0L
    private var screenW = 1
    private var screenH = 1

    // ------------------------------------------------------------------ yasam dongusu

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreen()
        buildPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try {
            removeExtra()
            panel?.let { wm.removeView(it) }
            menu?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ kucuk yardimcilar

    private fun ui(block: () -> Unit) {
        main.post { block() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun say(msg: String) {
        ui { statusView?.text = msg + "  [tik ok " + sent + " / iptal " + cancelled + "]" }
    }

    private fun refreshScreen() {
        val b = wm.maximumWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
    }

    private fun prefs() = getSharedPreferences("mubot", Context.MODE_PRIVATE)

    private fun pt(name: String): Pair<Float, Float> {
        val d = DEFAULTS[name] ?: Pair(0.5f, 0.5f)
        val p = prefs()
        return Pair(p.getFloat(name + "_x", d.first), p.getFloat(name + "_y", d.second))
    }

    private fun savePt(name: String, x: Float, y: Float) {
        prefs().edit().putFloat(name + "_x", x).putFloat(name + "_y", y).apply()
    }

    private fun pause(ms: Int) {
        var left = ms
        while (left > 0) {
            if (!running) throw StopEx()
            val s = if (left < 100) left else 100
            Thread.sleep(s.toLong())
            left -= s
        }
        if (!running) throw StopEx()
    }

    private fun fail(msg: String): Nothing {
        say(msg)
        throw StopEx()
    }

    // ------------------------------------------------------------------ ekran okuma

    private fun grab(): Bitmap? {
        val gap = 1100L - (System.currentTimeMillis() - lastShot)
        if (gap > 0) Thread.sleep(gap)
        lastShot = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hb = screenshot.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                    if (hw != null) {
                        result = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                    }
                    hb.close()
                } catch (e: Exception) {
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    private fun frac(b: Bitmap, name: String, hw: Float, hh: Float, pred: (Int) -> Boolean): Float {
        val p = pt(name)
        val cx = p.first * b.width
        val cy = p.second * b.height
        val x0 = maxOf(0, (cx - hw * b.width).toInt())
        val x1 = minOf(b.width - 1, (cx + hw * b.width).toInt())
        val y0 = maxOf(0, (cy - hh * b.height).toInt())
        val y1 = minOf(b.height - 1, (cy + hh * b.height).toInt())
        var hit = 0
        var tot = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                tot++
                if (pred(b.getPixel(x, y))) hit++
                x++
            }
            y++
        }
        return if (tot == 0) 0f else hit.toFloat() / tot
    }

    private fun isRed(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 170 && g < 90 && b < 90 && (r - g) > 100
    }

    /**
     * Haritadaki parlak yesil oyuncu ucgenini bulur. Koyu yesil rota noktalarini
     * esik disinda birakmaya calisir. Sonuc ekran oranlariyla dondurulur.
     */
    private fun findPlayerMarker(b: Bitmap): PointF? {
        val x0 = (b.width * 0.40f).toInt().coerceAtLeast(0)
        val x1 = (b.width * 0.78f).toInt().coerceAtMost(b.width - 1)
        val y0 = (b.height * 0.20f).toInt().coerceAtLeast(0)
        val y1 = (b.height * 0.76f).toInt().coerceAtMost(b.height - 1)
        var sumX = 0L
        var sumY = 0L
        var count = 0
        var minX = b.width
        var maxX = 0
        var minY = b.height
        var maxY = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = b.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val bl = Color.blue(c)
                // Oyuncu ucgeni parlak lime yesili; rotadaki noktalar daha koyu.
                if (g >= 125 && g > r * 1.42f && g > bl * 1.22f) {
                    sumX += x
                    sumY += y
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        // Cok az piksel gürültü olabilir; tüm haritayı kaplayan yeşil alanı da reddet.
        if (count < 5 || count > 500) return null
        if (maxX - minX > b.width * 0.06f || maxY - minY > b.height * 0.08f) return null
        return PointF((sumX.toFloat() / count) / b.width, (sumY.toFloat() / count) / b.height)
    }

    private fun distance(a: PointF, x: Float, y: Float): Float {
        val dx = a.x - x
        val dy = a.y - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun isOrange(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 200 && g in 110..210 && b < 90
    }

    private fun isSelected(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r in 150..235 && g in 85..165 && b < 100 && (r - g) > 40
    }

    private fun redFrac(b: Bitmap, m: Int): Float =
        frac(b, "boss" + m, 0.035f, 0.012f) { c -> isRed(c) }

    private fun bossAlive(b: Bitmap, m: Int): Boolean = redFrac(b, m) >= RED_THR

    private fun orangeAt(b: Bitmap, name: String): Boolean =
        frac(b, name, 0.012f, 0.020f) { c -> isOrange(c) } >= 0.12f

    private fun listLayoutOk(b: Bitmap): Boolean {
        for (m in 1..3) {
            if (frac(b, "map" + m, 0.05f, 0.008f) { c -> isSelected(c) } >= 0.4f) return true
        }
        return false
    }

    // ------------------------------------------------------------------ dokunma

    private fun tapPx(x: Float, y: Float): Boolean {
        for (i in 0 until 3) {
            val p = Path()
            p.moveTo(x, y)
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 70))
                .build()
            val latch = CountDownLatch(1)
            var ok = false
            val accepted = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            if (accepted) latch.await(2, TimeUnit.SECONDS)
            if (ok) {
                sent++
                return true
            }
            cancelled++
            Thread.sleep(300)
        }
        return false
    }

    private fun tapFrac(fx: Float, fy: Float) {
        refreshScreen()
        tapPx(fx * screenW, fy * screenH)
    }

    private fun tapPoint(name: String) {
        val p = pt(name)
        tapFrac(p.first, p.second)
    }

    // ------------------------------------------------------------------ bot adimlari

    private fun mapOpen(): Boolean {
        val b = grab() ?: return false
        return orangeAt(b, "closeMap")
    }

    private fun openMap() {
        for (i in 0 until 3) {
            if (mapOpen()) return
            tapPoint("minimap")
            pause(1500)
            if (mapOpen()) return
        }
        fail("Harita acilamadi. 'Ayar' ile minimap ve closeMap noktalarini kontrol et.")
    }

    private fun closeMapIfOpen() {
        val b = grab()
        if (b != null && orangeAt(b, "closeMap")) {
            tapPoint("closeMap")
            pause(700)
        }
    }

    private fun switchLine(line: Int) {
        closeMapIfOpen()
        say("Kanal " + line + "'e geciliyor")
        tapPoint("wire")
        pause(1500)
        tapPoint("ch" + line)
        pause(600)
        tapPoint("switchLine")
        pause(1500)
        val b = grab()
        if (b != null && orangeAt(b, "closeChannel")) {
            tapPoint("closeChannel")
            pause(500)
        }
        pause(LOAD_SEC * 1000)
    }

    private fun selectRaklion(m: Int) {
        openMap()
        // Liste ekraninda Raklion satiri gorunmuyorsa kontrollu sekilde asagi kaydir.
        var found = false
        repeat(4) {
            val b = grab() ?: fail("Harita listesi okunamadi")
            if (listLayoutOk(b)) found = true
            if (!found) {
                tapPoint("mapScroll")
                pause(450)
            }
        }
        tapPoint("raklionParent")
        pause(700)
        tapPoint(if (m == 1) "raklion1Child" else "raklion2Child")
        pause(1000)
        // Secimden sonra harita acik kalir; nokta ve boss etiketi ayridir.
    }

    /** Raklion 2'de tek, Raklion 1'de iki boss hedefi vardir. */
    private fun scanLine(line: Int): List<Int> {
        val result = mutableListOf<Int>()
        for (mapNo in listOf(2, 1)) {
            selectRaklion(mapNo)
            val b = grab() ?: fail("Ekran goruntusu alinamadi")
            val labelName = if (mapNo == 1) "boss1" else "boss2"
            val alive = redFrac(b, if (mapNo == 1) 1 else 2) >= RED_THR
            if (alive) result.add(mapNo)
            closeMapIfOpen()
        }
        return result
    }

    /** Harita acikken boss noktasina yurur; oyuncu ucgeni hareketi ve hedefe yakinligi izlenir. */
    @Volatile private var activeTarget = "targetR2"

    private fun walkToBoss(m: Int) {
        val name = activeTarget
        val bp = pt(name)
        val targetX = bp.first
        val targetY = bp.second + ICON_DY
        var shot = grab() ?: fail("Hareket baslangicinda ekran goruntusu alinamadi")
        var marker = findPlayerMarker(shot)
            ?: fail("Oyuncu yesil ucgeni bulunamadi. Harita acikken 'Test' yapip tekrar dene.")
        tapFrac(targetX, targetY)
        say("Raklion " + m + ": harita acik, oyuncu hareketi izleniyor")

        val started = System.currentTimeMillis()
        var lastProgressAt = started
        var lastMarker = marker
        var arrivalCount = 0
        var retries = 0
        val maxWalkMs = 120_000L
        while (running && System.currentTimeMillis() - started < maxWalkMs) {
            pause(2200)
            shot = grab() ?: continue
            val next = findPlayerMarker(shot)
            if (next == null) {
                say("Oyuncu isareti okunamadi; harita acik tutuluyor")
                continue
            }
            marker = next
            val targetDistance = distance(marker, targetX, targetY)
            if (targetDistance <= 0.035f) arrivalCount++ else arrivalCount = 0
            if (arrivalCount >= 3) {
                say("Boss konumuna ulasildi")
                return
            }
            if (distance(marker, lastMarker.x, lastMarker.y) >= 0.006f) {
                lastProgressAt = System.currentTimeMillis()
                lastMarker = marker
            }
            if (System.currentTimeMillis() - lastProgressAt >= 12_000L) {
                if (retries >= 2) {
                    fail("Hareket ilerlemiyor. Harita acik birakildi; boss koordinatini Ayar'dan kontrol et.")
                }
                say("Hareket durdu; boss hedefi yeniden seciliyor (" + (retries + 1) + "/2)")
                tapFrac(targetX, targetY)
                retries++
                lastProgressAt = System.currentTimeMillis()
                lastMarker = marker
            }
        }
        fail("Boss'a yuruyus 120 saniyede dogrulanamadi. Harita acik birakildi.")
    }

    private fun fightBoss(m: Int) {
        // Harita acik kalir; hedef koordinati boss etiketinden ayri tutulur.
        selectRaklion(m)
        walkToBoss(m)
        closeMapIfOpen()
        pause(700)
        tapPoint("manual")
        say("Oto saldiri basladi; 2 dakika sonra boss kontrol edilecek")
        val t0 = System.currentTimeMillis()
        var aliveChecks = 0
        while (running) {
            val elapsed = System.currentTimeMillis() - t0
            if (elapsed < 120_000L) pause(minOf(5_000L, 120_000L - elapsed).toInt())
            if (System.currentTimeMillis() - t0 >= 120_000L) {
                updateBuffThreshold()
                openMap()
                val b = grab()
                if (b == null) {
                    closeMapIfOpen()
                    say("Boss durumu okunamadi; sonraki hedefe gecilmiyor")
                    pause(5_000)
                    continue
                }
                val alive = bossAlive(b, m)
                closeMapIfOpen()
                if (!alive) {
                    // Tek bir olcumle olum karari verme; ikinci kontrolle dogrula.
                    pause(1500)
                    openMap()
                    val confirm = grab()
                    val stillAlive = confirm != null && bossAlive(confirm, m)
                    closeMapIfOpen()
                    if (!stillAlive) {
                        say("Boss'un kayboldugu iki kontrolle dogrulandi")
                        break
                    }
                    say("Boss hala canli; oto saldiri suruyor")
                } else {
                    aliveChecks++
                    say("Boss hala canli (kontrol " + aliveChecks + "); oto saldiri suruyor")
                }
                // Sonraki kontrol de 2 dakika sonra yapilir.
                val nextCheck = System.currentTimeMillis()
                while (running && System.currentTimeMillis() - nextCheck < 120_000L) {
                    pause(minOf(5_000L, 120_000L - (System.currentTimeMillis() - nextCheck)).toInt())
                }
            }
            if (System.currentTimeMillis() - t0 > FIGHT_TIMEOUT_SEC * 1000L) {
                say("10 dakika siniri: boss olum dogrulanamadi, guvenli durus")
                fail("Boss olum dogrulanamadi; rotaya devam etmek icin bot durduruldu")
            }
        }
        tapPoint("manual")
        pause(800)
    }

    private fun runLoop() {
        // Her calisma basinda buff noktasina git (Raklion 2 / kanal 1).
        switchLine(1)
        selectRaklion(2)
        walkToPoint("buffPoint", "Buff konumu")
        closeMapIfOpen()
        val initialStats = readCombatStats()
        if (initialStats != null) { baselineAtk = initialStats.atk; baselineDef = initialStats.def }
        var line = 1
        while (running) {
            if (line != 1 || prefs().getBoolean("routeStarted", false)) switchLine(line)
            prefs().edit().putBoolean("routeStarted", true).apply()
            // Raklion 2: bir boss; Raklion 1: iki boss. Emin olunmayan boss atlanmaz.
            for (mapNo in listOf(2, 1)) {
                selectRaklion(mapNo)
                val count = if (mapNo == 2) 1 else 2
                for (bossIndex in 0 until count) {
                    if (!running) throw StopEx()
                    activeTarget = when { mapNo == 2 -> "targetR2"; bossIndex == 0 -> "targetR1A"; else -> "targetR1B" }
                    val b = grab() ?: fail("Boss kontrolu icin ekran okunamadi")
                    val label = if (mapNo == 1) "boss1" else "boss2"
                    if (redFrac(b, if (mapNo == 1) 1 else 2) < RED_THR) {
                        say("Raklion $mapNo boss etiketi belirsiz; atlanmiyor, tekrar kontrol ediliyor")
                        pause(3000)
                        val retry = grab() ?: fail("Boss durumu belirsiz")
                        if (redFrac(retry, if (mapNo == 1) 1 else 2) < RED_THR) {
                            say("Boss bulunamadi; guvenli durus. Koordinat/etiket ayarini kontrol et")
                            fail("Boss canli/olu durumu guvenle belirlenemedi")
                        }
                    }
                    fightBoss(mapNo)
                    if (pendingBuff) {
                        selectRaklion(2)
                        walkToPoint("buffPoint", "Buff konumu")
                        closeMapIfOpen()
                        pendingBuff = false
                    }
                }
            }
            line = line % 3 + 1
        }
    }

    @Volatile private var pendingBuff = false
    private var baselineAtk: Int? = null
    private var baselineDef: Int? = null

    private data class CombatStats(val atk: Int, val def: Int)

    /** Ekranin sol ust bolgesindeki ATK/DEF metnini ML Kit OCR ile okumayi dener. */
    private fun readCombatStats(): CombatStats? {
        val b = grab() ?: return null
        val crop = Bitmap.createBitmap(b, 0, 0, (b.width * 0.42f).toInt().coerceAtLeast(1), (b.height * 0.28f).toInt().coerceAtLeast(1))
        val latch = CountDownLatch(1)
        var result: CombatStats? = null
        try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { vision ->
                    val txt = vision.text.uppercase()
                    fun numberAfter(vararg keys: String): Int? {
                        for (key in keys) {
                            val idx = txt.indexOf(key)
                            if (idx >= 0) {
                                val tail = txt.substring(idx + key.length).take(40)
                                val digits = Regex("[0-9][0-9, .]*").find(tail)?.value
                                val n = digits?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                                if (n != null) return n
                            }
                        }
                        return null
                    }
                    val atk = numberAfter("ATK", "ATTACK")
                    val def = numberAfter("DEF", "DEFENSE", "DEFENCE")
                    if (atk != null && def != null) result = CombatStats(atk, def)
                }
                .addOnCompleteListener { latch.countDown() }
            latch.await(4, TimeUnit.SECONDS)
        } catch (_: Exception) { }
        crop.recycle()
        return result
    }

    private fun updateBuffThreshold() {
        val stats = readCombatStats() ?: return
        if (baselineAtk == null || baselineDef == null) {
            baselineAtk = stats.atk
            baselineDef = stats.def
            say("ATK/DEF tabani kaydedildi: ${stats.atk}/${stats.def}")
            return
        }
        val atkDrop = baselineAtk!! - stats.atk
        val defDrop = baselineDef!! - stats.def
        if (atkDrop >= 850 || defDrop >= 350) {
            pendingBuff = true
            say("Buff gerekli (ATK -$atkDrop / DEF -$defDrop); mevcut savas bitince uygulanacak")
        }
    }

    private fun walkToPoint(pointName: String, description: String) {
        val p = pt(pointName)
        if (!mapOpen()) openMap()
        var marker = findPlayerMarker(grab() ?: fail("Harita okunamadi"))
            ?: fail("Oyuncu yesil ucgeni bulunamadi")
        tapFrac(p.first, p.second)
        val started = System.currentTimeMillis()
        var lastProgress = started
        var last = marker
        var arrived = 0
        while (running && System.currentTimeMillis() - started < 120_000L) {
            pause(2200)
            val b = grab() ?: continue
            marker = findPlayerMarker(b) ?: continue
            if (distance(marker, p.first, p.second) <= 0.035f) arrived++ else arrived = 0
            if (arrived >= 3) { say("$description noktasina ulasildi"); return }
            if (distance(marker, last.x, last.y) > 0.006f) { lastProgress = System.currentTimeMillis(); last = marker }
            if (System.currentTimeMillis() - lastProgress > 12_000L) {
                tapFrac(p.first, p.second)
                lastProgress = System.currentTimeMillis()
            }
        }
        fail("$description konumuna varis dogrulanamadi")
    }

    private fun start() {
        if (running) return
        if (worker?.isAlive == true) {
            say("Onceki islem bitiyor, biraz bekle")
            return
        }
        running = true
        updateUi()
        val t = Thread {
            try {
                runLoop()
            } catch (e: StopEx) {
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                say("Hata: " + e.message)
            }
            running = false
            ui { updateUi() }
        }
        worker = t
        t.start()
    }

    private fun stop() {
        running = false
        worker?.interrupt()
        say("Durduruldu")
        updateUi()
    }

    private fun toggle() {
        if (running) stop() else start()
    }

    private fun updateUi() {
        startBtn?.text = if (running) "■ Durdur" else "▶ Baslat"
        lineBtn?.text = "Baslangic kanali: " + startLine
        walkBtn?.text = "Yuruyus suresi: " + WALK_OPTIONS[walkIdx] + " sn"
    }

    // ------------------------------------------------------------------ test

    private fun runTest() {
        say("Test: ekran okunuyor")
        Thread {
            try {
                val b = grab()
                if (b == null) {
                    say("Ekran goruntusu alinamadi (Android 11+ gerekir)")
                } else {
                    val r1 = redFrac(b, 1)
                    val r2 = redFrac(b, 2)
                    val r3 = redFrac(b, 3)
                    val mo = orangeAt(b, "closeMap")
                    val co = orangeAt(b, "closeChannel")
                    say(
                        "Kirmizi oran b1=" + String.format("%.3f", r1) +
                            " b2=" + String.format("%.3f", r2) +
                            " b3=" + String.format("%.3f", r3) +
                            " (esik " + RED_THR + ")  harita X:" + mo + "  kanal X:" + co
                    )
                }
            } catch (e: Exception) {
                say("Test hatasi: " + e.message)
            }
            ui { showMarkers() }
        }.start()
    }

    // ------------------------------------------------------------------ panel ve menu

    private fun mkBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setPadding(dp(8), 0, dp(8), 0)
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        val bg = GradientDrawable()
        bg.setColor(0xCC222222.toInt())
        bg.cornerRadius = dp(6).toFloat()
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun addBtn(col: LinearLayout, btn: Button) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
        lp.topMargin = dp(3)
        col.addView(btn, lp)
    }

    private fun buildPanel() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val st = TextView(this)
        st.textSize = 11f
        st.setTextColor(Color.WHITE)
        st.setBackgroundColor(0xAA000000.toInt())
        st.setPadding(dp(4), dp(2), dp(4), dp(2))
        st.text = "Hazir"
        st.maxWidth = dp(260)
        statusView = st
        col.addView(st)

        val sb = mkBtn("▶ Baslat") { toggle() }
        startBtn = sb
        addBtn(col, sb)

        val lb = mkBtn("Baslangic kanali: 1") {
            startLine = startLine % 3 + 1
            updateUi()
        }
        lineBtn = lb
        addBtn(col, lb)

        val wb = mkBtn("Yuruyus suresi") {
            walkIdx = (walkIdx + 1) % WALK_OPTIONS.size
            updateUi()
        }
        walkBtn = wb
        addBtn(col, wb)

        addBtn(col, mkBtn("Test") { runTest() })
        addBtn(col, mkBtn("Ayar") { toggleMenu() })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = (screenH * 0.50f).toInt()
        wm.addView(col, lp)
        panel = col
        updateUi()
    }

    private fun toggleMenu() {
        val old = menu
        if (old != null) {
            try {
                wm.removeView(old)
            } catch (e: Exception) {
            }
            menu = null
            return
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setBackgroundColor(0xAA000000.toInt())
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        for ((i, n) in NAMES.withIndex()) {
            val btn = mkBtn(n + ": " + (LABELS[n] ?: n)) {
                beginCapture(n)
            }
            btn.textSize = 11f
            addBtn(if (i < 8) left else right, btn)
        }
        addBtn(right, mkBtn("Menuyu kapat") { toggleMenu() })
        row.addView(left)
        row.addView(right)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(200)
        lp.y = dp(10)
        wm.addView(row, lp)
        menu = row
    }

    private fun removeExtra() {
        val v = extra
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
            extra = null
        }
    }

    /** Sonraki dokunusu kaydeden tam ekran saydam katman. Oyunda ilgili ekrani acik tutup kullan. */
    private fun beginCapture(name: String) {
        if (menu != null) toggleMenu()
        removeExtra()
        val fl = FrameLayout(this)
        fl.setBackgroundColor(0x33FFFFFF)
        val tv = TextView(this)
        tv.text = "'" + name + "' icin dokun:  " + (LABELS[name] ?: name)
        tv.textSize = 18f
        tv.setTextColor(Color.BLACK)
        tv.setBackgroundColor(0xDDFFFF00.toInt())
        tv.setPadding(dp(10), dp(6), dp(10), dp(6))
        fl.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
        fl.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                refreshScreen()
                savePt(name, ev.rawX / screenW, ev.rawY / screenH)
                removeExtra()
                Toast.makeText(this, "Kaydedildi: " + name, Toast.LENGTH_SHORT).show()
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(fl, lp)
        extra = fl
    }

    /** Kayitli tum noktalari 5 saniye boyunca ekranda gosterir. */
    private fun showMarkers() {
        removeExtra()
        refreshScreen()
        val fl = FrameLayout(this)
        for (n in NAMES) {
            val p = pt(n)
            val tv = TextView(this)
            tv.text = "● " + n
            tv.textSize = 10f
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(0xCCCC0000.toInt())
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = (p.first * screenW).toInt() - dp(5)
            lp.topMargin = (p.second * screenH).toInt() - dp(8)
            fl.addView(tv, lp)
        }
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(fl, wlp)
        extra = fl
        main.postDelayed({
            if (extra === fl) removeExtra()
        }, 5000)
    }
}
