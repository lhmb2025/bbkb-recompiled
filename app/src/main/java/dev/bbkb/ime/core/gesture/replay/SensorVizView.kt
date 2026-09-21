package dev.bbkb.ime.core.gesture.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import dev.bbkb.ime.core.shared.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capacitive-keypad sensor mapper.
 *
 * The data model lives in [SensorViz] (an object), NOT in the view, so capture + the periodic logcat
 * dump + file export all work even if the on-screen overlay fails to attach to the IME window (which
 * is finicky on a PKB). [SensorVizView] is purely a renderer for the model.
 *
 * Pipeline: BlackBerryIME.onGenericMotionEvent forwards each raw SOURCE_TOUCHPAD sample (1080x525)
 * to [SensorViz.addPoint] (and to the view's [SensorVizView.plot] if present), then swallows the
 * event so typing is suppressed while mapping. This is upstream of the Nuance engine, so it works
 * even when the KDB fails to load.
 */
object SensorViz {
    const val PREF_KEY = "ckb_sensor_viz"
    const val SENSOR_W = 1080f
    const val SENSOR_H = 525f
    const val Y_BIN = 3
    const val X_BIN = 5
    private const val MAX_POINTS = 40000

    @Volatile
    var active = false

    // Proposed mapping model drawn as a reference grid on the canvas (tweak freely to test):
    // a dead strip at the top, then equal-height letter rows. Default = the 50px + 128px hypothesis.
    @Volatile var refDeadTop = 50f
    @Volatile var refRowH = 128f

    // Audit GD-24: allocate the 320 KB of sample buffers on first use, not at class init. The
    // object is touched once per input session just to read `active` (SensorVizOverlay), and the
    // visualizer is off by default — an IME is one of the most heap-constrained processes on the
    // device, so the buffers must not exist until something actually records into them.
    val rawX: FloatArray by lazy(LazyThreadSafetyMode.NONE) { FloatArray(MAX_POINTS) }
    val rawY: FloatArray by lazy(LazyThreadSafetyMode.NONE) { FloatArray(MAX_POINTS) }
    var rawHead = 0
    var rawCount = 0
    val yHist: IntArray by lazy(LazyThreadSafetyMode.NONE) { IntArray((SENSOR_H.toInt() / Y_BIN) + 1) }
    val xHist: IntArray by lazy(LazyThreadSafetyMode.NONE) { IntArray((SENSOR_W.toInt() / X_BIN) + 1) }
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var total = 0L

    fun addPoint(x: Float, y: Float) {
        rawX[rawHead] = x; rawY[rawHead] = y
        rawHead = (rawHead + 1) % MAX_POINTS
        if (rawCount < MAX_POINTS) rawCount++
        total++
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        val yb = (y.toInt().coerceIn(0, SENSOR_H.toInt())) / Y_BIN
        if (yb in yHist.indices) yHist[yb]++
        val xb = (x.toInt().coerceIn(0, SENSOR_W.toInt())) / X_BIN
        if (xb in xHist.indices) xHist[xb]++
        if (total % 800L == 0L) logSummary()
    }

    fun clear() {
        rawHead = 0; rawCount = 0; total = 0
        minX = Float.MAX_VALUE; maxX = -Float.MAX_VALUE
        minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE
        java.util.Arrays.fill(yHist, 0)
        java.util.Arrays.fill(xHist, 0)
    }

    fun computeBands(hist: IntArray, bin: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val maxBin = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
        val thr = (maxBin * 0.05f).coerceAtLeast(2f)
        var i = 0
        while (i < hist.size) {
            if (hist[i] >= thr) {
                val start = i
                while (i < hist.size && hist[i] >= thr) i++
                out.add(Pair(start * bin, i * bin))
            } else i++
        }
        return out
    }

    fun gaps(bands: List<Pair<Int, Int>>, max: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var prev = 0
        for (b in bands) {
            if (b.first > prev) out.add(Pair(prev, b.first))
            prev = b.second
        }
        if (prev < max) out.add(Pair(prev, max))
        return out
    }

    fun logSummary() {
        Logger.info("XT9SENSOR", "samples=$total  X[${minX.toInt()}..${maxX.toInt()}]  Y[${minY.toInt()}..${maxY.toInt()}]  (sensor 1080x525)")
        val yb = computeBands(yHist, Y_BIN)
        for (band in yb) Logger.info("XT9SENSOR", "Y_LIVE ${band.first}..${band.second}  height=${band.second - band.first}")
        for (g in gaps(yb, SENSOR_H.toInt())) Logger.info("XT9SENSOR", "Y_GAP  ${g.first}..${g.second}  height=${g.second - g.first}")
        for (band in computeBands(xHist, X_BIN)) Logger.info("XT9SENSOR", "X_LIVE ${band.first}..${band.second}  width=${band.second - band.first}")
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("# BlackBerry capacitive keypad sensor map\n")
        sb.append("# device=${android.os.Build.DEVICE} sensor=1080x525 samples=$total\n")
        sb.append("# observed bounds: X[${minX.toInt()}..${maxX.toInt()}] Y[${minY.toInt()}..${maxY.toInt()}]\n\n")
        sb.append("## live Y bands (sensor px) — letter strips vs dead gaps\n")
        val yb = computeBands(yHist, Y_BIN)
        for (band in yb) sb.append("Y_LIVE ${band.first}..${band.second}  (height ${band.second - band.first})\n")
        for (g in gaps(yb, SENSOR_H.toInt())) sb.append("Y_GAP  ${g.first}..${g.second}  (height ${g.second - g.first})\n")
        sb.append("\n## live X bands (sensor px)\n")
        for (band in computeBands(xHist, X_BIN)) sb.append("X_LIVE ${band.first}..${band.second}  (width ${band.second - band.first})\n")
        sb.append("\n## Y histogram (binPx=$Y_BIN): binStartPx,count\n")
        for (i in yHist.indices) if (yHist[i] > 0) sb.append("${i * Y_BIN},${yHist[i]}\n")
        sb.append("\n## raw points (x,y)\n")
        var n = 0
        val startIdx = if (rawCount < MAX_POINTS) 0 else rawHead
        while (n < rawCount) {
            val p = (startIdx + n) % MAX_POINTS
            sb.append("${rawX[p].toInt()},${rawY[p].toInt()}\n")
            n++
        }
        return sb.toString()
    }

    /** Write the report to the app's external files dir and dump bands to logcat. Returns the file. */
    fun export(context: Context): File? {
        logSummary()
        if (total == 0L) return null
        return try {
            val dir = context.getExternalFilesDir(null)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "sensor_viz_${android.os.Build.DEVICE}_$stamp.txt")
            f.writeText(buildReport())
            f
        } catch (e: Throwable) {
            Logger.warn("XT9SENSOR", "export failed: ${e.message}")
            null
        }
    }
}

class SensorVizView(context: Context) : View(context) {

    private val content = RectF()
    private var accum: Bitmap? = null
    private var accumCanvas: Canvas? = null

    private val pBg = Paint().apply { color = Color.parseColor("#EE0E1116") }
    private val pField = Paint().apply { color = Color.parseColor("#FF161B22") }
    private val pGrid = Paint().apply { color = Color.parseColor("#2AFFFFFF"); strokeWidth = 1f }
    private val pGridStrong = Paint().apply { color = Color.parseColor("#66FFD54A"); strokeWidth = 2f }
    private val pFrame = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pPoint = Paint().apply { color = Color.parseColor("#9900E5FF"); isAntiAlias = true }
    private val pBand = Paint().apply { color = Color.parseColor("#3329FF9E") }
    private val pText = Paint().apply { color = Color.WHITE; textSize = 26f; isAntiAlias = true }
    private val pTextDim = Paint().apply { color = Color.parseColor("#CCBFD8FF"); textSize = 22f; isAntiAlias = true }
    private val pBtn = Paint().apply { color = Color.parseColor("#CC1565C0") }
    private val pBtnText = Paint().apply { color = Color.WHITE; textSize = 30f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val pDead = Paint().apply { color = Color.parseColor("#33FF5252") }          // dead-strip shade
    private val pRefLine = Paint().apply { color = Color.parseColor("#FFFF8F00"); strokeWidth = 3f } // proposed row separators
    private val pRefText = Paint().apply { color = Color.parseColor("#FFFFB74D"); textSize = 22f; isAntiAlias = true }

    init { isClickable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hAvail = MeasureSpec.getSize(heightMeasureSpec)
        val ideal = (w * SensorViz.SENSOR_H / SensorViz.SENSOR_W).toInt() + 60
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> hAvail
            MeasureSpec.AT_MOST -> minOf(ideal, hAvail)
            else -> ideal
        }
        setMeasuredDimension(w, h.coerceAtLeast(120))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Logger.info("XT9SENSOR", "viz overlay attached: w=$w h=$h (if h is tiny the IME window is short)")
        val avail = (h - 60).coerceAtLeast(1).toFloat()
        var rw = w.toFloat()
        var rh = rw * SensorViz.SENSOR_H / SensorViz.SENSOR_W
        if (rh > avail) { rh = avail; rw = rh * SensorViz.SENSOR_W / SensorViz.SENSOR_H }
        val left = (w - rw) / 2f
        content.set(left, 0f, left + rw, rh)
        if (w > 0 && h > 0) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            accum = bmp
            accumCanvas = Canvas(bmp)
            // Repopulate from the model so the plot survives view re-creation.
            val startIdx = if (SensorViz.rawCount < SensorViz.rawX.size) 0 else SensorViz.rawHead
            var n = 0
            while (n < SensorViz.rawCount) {
                val p = (startIdx + n) % SensorViz.rawX.size
                accumCanvas?.drawCircle(sx(SensorViz.rawX[p]), sy(SensorViz.rawY[p]), 3.5f, pPoint)
                n++
            }
        }
    }

    private fun sx(x: Float) = content.left + x / SensorViz.SENSOR_W * content.width()
    private fun sy(y: Float) = content.top + y / SensorViz.SENSOR_H * content.height()

    /** Draw one already-recorded sample. (Data is stored by SensorViz.addPoint, called separately.) */
    fun plot(x: Float, y: Float) {
        accumCanvas?.drawCircle(sx(x), sy(y), 3.5f, pPoint)
        postInvalidateOnAnimation()
    }

    private fun btnTop() = height - 56f
    private fun clearRect() = RectF(8f, btnTop(), width / 3f - 4f, height - 8f)
    private fun exportRect() = RectF(width / 3f + 4f, btnTop(), 2f * width / 3f - 4f, height - 8f)
    private fun closeRect() = RectF(2f * width / 3f + 4f, btnTop(), width - 8f, height - 8f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val x = event.x; val y = event.y
            when {
                clearRect().contains(x, y) -> { SensorViz.clear(); accum?.eraseColor(Color.TRANSPARENT); invalidate() }
                exportRect().contains(x, y) -> {
                    val f = SensorViz.export(context)
                    Toast.makeText(context, if (f != null) "Saved: ${f.absolutePath}" else "No samples yet", Toast.LENGTH_LONG).show()
                }
                closeRect().contains(x, y) -> {
                    SensorViz.active = false
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putBoolean(SensorViz.PREF_KEY, false).apply()
                    visibility = GONE
                }
            }
        }
        return true
    }

    private fun drawBtn(c: Canvas, r: RectF, label: String) {
        c.drawRoundRect(r, 8f, 8f, pBtn)
        c.drawText(label, r.centerX(), r.centerY() + 10f, pBtnText)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pBg)
        c.drawRect(content, pField)

        var gx = 0f
        while (gx <= SensorViz.SENSOR_W) { c.drawLine(sx(gx), content.top, sx(gx), content.bottom, pGrid); gx += 108f }
        for (f in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            val yy = content.top + content.height() * f
            c.drawLine(content.left, yy, content.right, yy, if (f == 0.75f) pGridStrong else pGrid)
        }

        if (SensorViz.total > 0) {
            val maxBin = (SensorViz.yHist.maxOrNull() ?: 1).coerceAtLeast(1)
            val thr = (maxBin * 0.05f).coerceAtLeast(2f)
            var b = 0
            while (b < SensorViz.yHist.size) {
                if (SensorViz.yHist[b] >= thr) {
                    c.drawRect(content.left, sy((b * SensorViz.Y_BIN).toFloat()), content.right, sy(((b + 1) * SensorViz.Y_BIN).toFloat()), pBand)
                }
                b++
            }
        }

        accum?.let { c.drawBitmap(it, 0f, 0f, null) }

        // Proposed reference model: dead strip + equal-height rows (orange), to compare against the
        // actual touch density (green bands / points). Adjust SensorViz.refDeadTop / refRowH to test.
        run {
            val dead = SensorViz.refDeadTop
            val rh = SensorViz.refRowH
            c.drawRect(content.left, sy(0f), content.right, sy(dead), pDead)
            val labels = arrayOf("R1", "R2", "R3", "SPACE")
            var top = dead
            var i = 0
            while (top < SensorViz.SENSOR_H && i < 4) {
                c.drawLine(content.left, sy(top), content.right, sy(top), pRefLine)
                c.drawText("${labels[i]} @${top.toInt()}", content.left + 6f, sy(top) + 22f, pRefText)
                top += rh
                i++
            }
            c.drawText("DEAD ${dead.toInt()}px / rows ${rh.toInt()}px", content.left + 6f, sy(dead) - 6f, pRefText)
        }

        c.drawRect(content, pFrame)

        c.drawText("SENSOR VIZ  1080x525  pts=${SensorViz.total}", 12f, 28f, pText)
        if (SensorViz.total > 0) {
            c.drawText("X[${SensorViz.minX.toInt()}..${SensorViz.maxX.toInt()}]  Y[${SensorViz.minY.toInt()}..${SensorViz.maxY.toInt()}]", 12f, 54f, pTextDim)
            val bands = SensorViz.computeBands(SensorViz.yHist, SensorViz.Y_BIN)
            c.drawText("live Y: " + bands.joinToString(" ") { "${it.first}-${it.second}" }, 12f, 80f, pTextDim)
        } else {
            c.drawText("sweep the physical keyboard…", 12f, 54f, pTextDim)
        }

        drawBtn(c, clearRect(), "CLEAR")
        drawBtn(c, exportRect(), "EXPORT")
        drawBtn(c, closeRect(), "CLOSE")
    }
}
