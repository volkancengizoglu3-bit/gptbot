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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.text.InputType
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
 *  - Raklion 1/2 konumlari sohbet sekmesinden kaydedilmis dokunuslarla acilir.
 *  - Boss hedefleri harita uzerinde secilir; harita listesiyle bolge degisimi yapilmaz.
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

    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var extra: View? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var lineBtn: Button? = null

    @Volatile
    private var running = false
    private var worker: Thread? = null

    private var startLine = 1
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

    private data class TextHit(val text: String, val x: Float, val y: Float)

    /** ML Kit OCR; coordinates are normalized to the captured screenshot. */
    private fun readScreenText(bitmap: Bitmap): List<TextHit> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val latch = CountDownLatch(1)
        var hits: List<TextHit> = emptyList()
        var failure: Exception? = null
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                hits = result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        TextHit(
                            line.text,
                            ((box.left + box.right) / 2f) / bitmap.width,
                            ((box.top + box.bottom) / 2f) / bitmap.height
                        )
                    }
                }
                latch.countDown()
            }
            .addOnFailureListener { e ->
                failure = e
                latch.countDown()
            }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            recognizer.close()
            throw IllegalStateException("OCR zaman asimina ugradi")
        }
        recognizer.close()
        failure?.let { throw IllegalStateException("OCR basarisiz", it) }
        return hits
    }

    private fun normalizedText(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun findRaklionParent(b: Bitmap): TextHit? =
        readScreenText(b).firstOrNull { hit ->
            val t = normalizedText(hit.text)
            t.contains("raklion") && !t.contains("raklion1") &&
                !t.contains("raklion2") && !t.contains("raklion3")
        }

    private fun findRaklionChild(b: Bitmap, m: Int): TextHit? {
        val target = "raklion" + m
        return readScreenText(b).firstOrNull { hit ->
            normalizedText(hit.text).contains(target)
        }
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

    // Harita listesini gercek bir yukari kaydirma hareketiyle asagi indir.
    // Liste daha asagidaki bolgeleri gostersin diye parmak alttan uste gider.
    private fun swipeMapListDown(): Boolean {
        refreshScreen()
        val x = screenW * 0.22f
        val startY = screenH * 0.80f
        val endY = screenH * 0.34f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 550))
            .build()
        val latch = CountDownLatch(1)
        var completed = false
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (accepted) latch.await(2, TimeUnit.SECONDS)
        if (completed) {
            sent++
            pause(500)
            return true
        }
        cancelled++
        return false
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

        // Raklion ana satirini OCR ile gercekten bulana kadar kucuk adimlarla kaydir.
        var parent: TextHit? = null
        repeat(8) {
            val b = grab() ?: fail("Harita listesi okunamadi")
            parent = findRaklionParent(b)
            if (parent != null) return@repeat
            if (!swipeMapListDown()) fail("Harita listesi kaydirilamadi")
            pause(350)
        }
        val parentHit = parent ?: fail("Raklion ana satiri 8 kaydirmada bulunamadi; yanlis satira tiklanmadi")
        if (!tapPx(parentHit.x * screenW, parentHit.y * screenH)) {
            fail("Raklion ana satirina dokunulamadi")
        }
        pause(700)

        // Alt satirlar acilinca hedef olan Raklion 1/2 yazisini OCR ile bul ve merkezine dokun.
        var child: TextHit? = null
        repeat(3) {
            val b = grab() ?: fail("Raklion alt listesi okunamadi")
            child = findRaklionChild(b, m)
            if (child != null) return@repeat
            pause(400)
        }
        val childHit = child ?: fail("Raklion $m alt satiri ekranda okunamadi; yanlis haritaya tiklanmadi")
        if (!tapPx(childHit.x * screenW, childHit.y * screenH)) {
            fail("Raklion $m alt satirina dokunulamadi")
        }
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
                if (prefs().getBoolean("buffEnabled", true)) updateBuffThreshold()
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
        val buffEnabled = { prefs().getBoolean("buffEnabled", true) }
        if (buffEnabled()) {
            switchLine(1)
            selectRaklion(2)
            walkToPoint("buffPoint", "Buff konumu")
            closeMapIfOpen()
            verifyStatsAfterBuff()
        }

        // Istenen sira: Raklion 1 kanal 1/2/3, sonra Raklion 2 kanal 1/2/3.
        while (running) {
            for (mapNo in listOf(1, 2)) {
                for (line in 1..3) {
                    if (!running) throw StopEx()
                    switchLine(line)
                    selectRaklion(mapNo)
                    val count = if (mapNo == 1) 2 else 1
                    for (bossIndex in 0 until count) {
                        if (!running) throw StopEx()
                        val target = when {
                            mapNo == 2 -> "targetR2"
                            bossIndex == 0 -> "targetR1A"
                            else -> "targetR1B"
                        }
                        activeTarget = target
                        val bossNumber = if (mapNo == 1) 1 else 2
                        val first = grab() ?: fail("Boss durumu okunamadi")
                        val firstScore = redFrac(first, bossNumber)
                        if (firstScore < RED_THR) {
                            pause(1800)
                            val second = grab() ?: fail("Boss durumu tekrar okunamadi")
                            val secondScore = redFrac(second, bossNumber)
                            if (secondScore < RED_THR) {
                                // Dusuk kirmizi oranini olu kabul edip atlamiyoruz:
                                // etiket/ikon okunamadigi durumda yanlis hedefe yurume riski var.
                                say("Raklion $mapNo boss ikonu canli olarak dogrulanamadi; guvenli durus")
                                fail("Boss canli/olu durumu kesin degil; hedef atlanmadi")
                            }
                        }
                        fightBoss(mapNo)
                        handleBuffIfNeeded(buffEnabled(), line, mapNo)
                    }
                }
            }
        }
    }

    private fun handleBuffIfNeeded(enabled: Boolean, resumeLine: Int, resumeMap: Int) {
        if (!enabled) {
            pendingBuff = false
            return
        }
        val stats = readCombatStats() ?: run {
            say("ATK/DEF okunamadi; buff karari verilmedi")
            return
        }
        val targetAtk = prefs().getInt("targetAtk", 0)
        val targetDef = prefs().getInt("targetDef", 0)
        val needAtk = targetAtk > 0 && stats.atk < targetAtk
        val needDef = targetDef > 0 && stats.def < targetDef
        if (!needAtk && !needDef) {
            pendingBuff = false
            say("ATK/DEF hedefleri karsilaniyor: ${stats.atk}/${stats.def}")
            return
        }
        say("Buff noktasina gidiliyor; mevcut ATK/DEF ${stats.atk}/${stats.def}")
        switchLine(1)
        selectRaklion(2)
        walkToPoint("buffPoint", "Buff konumu")
        closeMapIfOpen()
        verifyStatsAfterBuff()
        pendingBuff = false
        // Buff noktasindan sonra tam olarak kaldigimiz kanal ve haritaya don.
        switchLine(resumeLine)
        selectRaklion(resumeMap)
    }

    private fun verifyStatsAfterBuff() {
        pause(1500)
        val stats = readCombatStats()
        if (stats == null) {
            say("Buff sonrasi ATK/DEF okunamadi; rota kaldigi yerden devam edecek")
            return
        }
        say("Buff sonrasi ATK/DEF: ${stats.atk}/${stats.def}")
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
        if (!prefs().getBoolean("buffEnabled", true)) return
        val stats = readCombatStats() ?: return
        val targetAtk = prefs().getInt("targetAtk", 0)
        val targetDef = prefs().getInt("targetDef", 0)
        if ((targetAtk > 0 && stats.atk < targetAtk) ||
            (targetDef > 0 && stats.def < targetDef)) {
            pendingBuff = true
            say("Buff hedefi altinda: ATK/DEF ${stats.atk}/${stats.def}")
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

        addBtn(col, mkBtn("Test") { runTest() })
        addBtn(col, mkBtn("Ayar") { showSettings() })
        addBtn(col, mkBtn("Nokta Ayarlari") { toggleMenu() })

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

    private fun showSettings() {
        if (running) {
            Toast.makeText(this, "Ayar degistirmek icin once botu durdur", Toast.LENGTH_LONG).show()
            return
        }
        val old = menu
        if (old != null) {
            try { wm.removeView(old) } catch (_: Exception) {}
            menu = null
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(10), dp(12), dp(10))
        root.setBackgroundColor(0xEE202020.toInt())

        val title = TextView(this)
        title.text = "Buff Ayarlari"
        title.textSize = 16f
        title.setTextColor(Color.WHITE)
        root.addView(title)

        val toggle = Switch(this)
        toggle.text = "Buff kontrolu aktif"
        toggle.setTextColor(Color.WHITE)
        toggle.isChecked = prefs().getBoolean("buffEnabled", true)
        root.addView(toggle)

        fun field(label: String, key: String): EditText {
            val caption = TextView(this)
            caption.text = label
            caption.setTextColor(Color.WHITE)
            root.addView(caption)
            val edit = EditText(this)
            edit.inputType = InputType.TYPE_CLASS_NUMBER
            edit.setSingleLine(true)
            edit.setText(prefs().getInt(key, 0).let { if (it == 0) "" else it.toString() })
            edit.hint = "0 = bu degeri kontrol etme"
            edit.setTextColor(Color.WHITE)
            edit.setHintTextColor(0xFFBBBBBB.toInt())
            root.addView(edit, LinearLayout.LayoutParams(dp(230), dp(48)))
            return edit
        }
        val atkInput = field("Hedef ATK", "targetAtk")
        val defInput = field("Hedef DEF", "targetDef")
        val help = TextView(this)
        help.text = "Hedef 0 ise ilgili deger kontrol edilmez. Degisiklikler kaydedilir."
        help.setTextColor(Color.LTGRAY)
        help.textSize = 11f
        root.addView(help)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val save = mkBtn("Kaydet") {
            val atk = atkInput.text.toString().trim().toIntOrNull()
            val def = defInput.text.toString().trim().toIntOrNull()
            if (atk == null || def == null || atk < 0 || def < 0) {
                Toast.makeText(this, "ATK/DEF icin 0 veya pozitif tam sayi gir", Toast.LENGTH_LONG).show()
            } else {
                prefs().edit()
                    .putBoolean("buffEnabled", toggle.isChecked)
                    .putInt("targetAtk", atk)
                    .putInt("targetDef", def)
                    .apply()
                try { wm.removeView(root) } catch (_: Exception) {}
                if (menu === root) menu = null
                Toast.makeText(this, "Buff ayarlari kaydedildi", Toast.LENGTH_SHORT).show()
            }
        }
        val close = mkBtn("Kapat") {
            try { wm.removeView(root) } catch (_: Exception) {}
            if (menu === root) menu = null
        }
        addBtn(actions, save)
        addBtn(actions, close)
        root.addView(actions)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(200)
        lp.y = dp(10)
        wm.addView(root, lp)
        menu = root
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
