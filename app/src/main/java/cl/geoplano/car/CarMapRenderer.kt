package cl.geoplano.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import cl.geoplano.data.MapEntry
import cl.geoplano.data.MapStore
import cl.geoplano.data.Placemark
import cl.geoplano.data.Track
import cl.geoplano.render.PdfRenderPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja el plano PDF directamente en la superficie de la pantalla del auto
 * (Android Auto, categoría navegación). Maneja pan/zoom del host y el modo "seguir posición".
 */
class CarMapRenderer(private val carContext: CarContext, private val scope: CoroutineScope) : SurfaceCallback {

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 160
    private val visibleArea = Rect()

    private var map: MapEntry? = null
    private var base: Bitmap? = null
    private var detail: Bitmap? = null
    private val detailRect = RectF()
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    /** Centro de la vista en coordenadas de página y escala px/pt. */
    private var cx = 0f
    private var cy = 0f
    private var scale = 0f
    private var fitScale = 1f

    var follow = true
    var location: Location? = null
        set(v) {
            field = v
            if (follow && v != null && map?.contains(v.latitude, v.longitude) == true) centerOnLocation(false) else draw()
        }
    var placemarks: List<Placemark> = emptyList()
        set(v) { field = v; draw() }
    var tracks: List<Track> = emptyList()
        set(v) { field = v; draw() }
    var activeTrack: Track? = null
        set(v) { field = v; draw() }

    private val handler = Handler(Looper.getMainLooper())
    private val m = Matrix()

    private val density get() = dpi / 160f
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 136, 229) }
    private val acc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 30, 136, 229) }
    private val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 30, 136, 229) }
    private val trk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val pin = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 110, 110); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val crossHalo = Paint(cross).apply { color = Color.argb(170, 255, 255, 255) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 30, 136, 229); style = Paint.Style.STROKE
    }

    fun setMap(entry: MapEntry?) {
        if (entry?.id == map?.id && base != null) return
        map = entry
        base = null
        detail = null
        scale = 0f
        loadJob?.cancel()
        if (entry == null) { draw(); return }
        loadJob = scope.launch {
            val bmp = runCatching { PdfRenderPool.renderFull(MapStore.pdfFile(entry), 2048) }.getOrNull()
            if (map?.id != entry.id) return@launch
            base = bmp
            resetView()
        }
    }

    private fun resetView() {
        val e = map ?: return
        val va = area()
        if (va.width() <= 0) return
        val n = e.neatline ?: doubleArrayOf(0.0, 0.0, e.pageWidth, e.pageHeight)
        fitScale = min(va.width() / (n[2] - n[0]).toFloat(), va.height() / (n[3] - n[1]).toFloat())
        scale = fitScale
        cx = ((n[0] + n[2]) / 2).toFloat()
        cy = ((n[1] + n[3]) / 2).toFloat()
        val loc = location
        if (follow && loc != null && e.contains(loc.latitude, loc.longitude)) {
            scale = fitScale * 3f
            centerOnLocation(false)
        } else {
            draw(); scheduleDetail()
        }
    }

    fun centerOnLocation(zoom: Boolean) {
        val e = map ?: return
        val loc = location ?: return
        val t = e.transform ?: return
        val p = t.toPage(loc.latitude, loc.longitude)
        cx = p[0].toFloat(); cy = p[1].toFloat()
        if (zoom && scale < fitScale * 3f) scale = fitScale * 3f
        draw(); scheduleDetail()
    }

    fun zoom(f: Float) {
        if (scale == 0f) return
        scale = (scale * f).coerceIn(fitScale * 0.5f, fitScale * 50f)
        draw(); scheduleDetail()
    }

    private fun area(): Rect = if (visibleArea.width() > 0) visibleArea else Rect(0, 0, width, height)

    // ---------- SurfaceCallback ----------

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface
        width = container.width
        height = container.height
        dpi = container.dpi
        text.textSize = 14f * density
        trk.strokeWidth = 5f * density
        cross.strokeWidth = 3.5f * density
        crossHalo.strokeWidth = 7f * density
        guide.strokeWidth = 2.5f * density
        guide.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
        if (scale == 0f) resetView() else draw()
    }

    override fun onVisibleAreaChanged(area: Rect) {
        visibleArea.set(area)
        if (scale == 0f) resetView() else draw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (scale == 0f) return
        follow = false
        cx += distanceX / scale
        cy += distanceY / scale
        draw(); scheduleDetail()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scale == 0f) return
        // zoom con foco: el punto bajo el foco queda fijo
        val va = area()
        val fx = if (focusX < 0) va.exactCenterX() else focusX
        val fy = if (focusY < 0) va.exactCenterY() else focusY
        val px = cx + (fx - va.exactCenterX()) / scale
        val py = cy + (fy - va.exactCenterY()) / scale
        val ns = (scale * scaleFactor).coerceIn(fitScale * 0.5f, fitScale * 50f)
        cx = px - (fx - va.exactCenterX()) / ns
        cy = py - (fy - va.exactCenterY()) / ns
        scale = ns
        draw(); scheduleDetail()
    }

    // ---------- dibujo ----------

    private fun buildMatrix() {
        val va = area()
        m.reset()
        m.postTranslate(-cx, -cy)
        m.postScale(scale, scale)
        m.postTranslate(va.exactCenterX(), va.exactCenterY())
    }

    fun draw() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas: Canvas = try { s.lockCanvas(null) } catch (e: Exception) { Log.w(TAG, "lockCanvas", e); return }
        try {
            val dark = carContext.isDarkMode
            canvas.drawColor(if (dark) Color.rgb(28, 30, 33) else Color.rgb(222, 222, 216))
            val e = map
            val b = base
            if (e == null || b == null || scale == 0f) {
                text.color = if (dark) Color.LTGRAY else Color.DKGRAY
                val msg = if (e == null) "Elige un plano en “Planos”" else "Cargando plano…"
                canvas.drawText(msg, area().exactCenterX() - text.measureText(msg) / 2, area().exactCenterY(), text)
                return
            }
            buildMatrix()
            canvas.save()
            canvas.concat(m)
            val page = RectF(0f, 0f, e.pageWidth.toFloat(), e.pageHeight.toFloat())
            canvas.drawRect(page, white)
            canvas.drawBitmap(b, null, page, bmpPaint)
            detail?.let { canvas.drawBitmap(it, null, detailRect, bmpPaint) }
            canvas.restore()

            val t = e.transform ?: return
            val tmp = FloatArray(2)
            fun toScreen(lat: Double, lon: Double) {
                val p = t.toPage(lat, lon)
                tmp[0] = p[0].toFloat(); tmp[1] = p[1].toFloat()
                m.mapPoints(tmp)
            }
            fun drawTrack(tr: Track, color: Int) {
                if (tr.points.size < 2) return
                val path = Path()
                tr.points.forEachIndexed { i, p ->
                    toScreen(p.lat, p.lon)
                    if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
                }
                trk.color = color
                canvas.drawPath(path, trk)
            }
            tracks.forEach { drawTrack(it, Color.rgb(232, 142, 61)) }
            activeTrack?.let { drawTrack(it, Color.rgb(211, 47, 47)) }

            val r = 8f * density
            for (p in placemarks) {
                toScreen(p.lat, p.lon)
                pin.color = p.color
                canvas.drawCircle(tmp[0], tmp[1], r + 2 * density, white)
                canvas.drawCircle(tmp[0], tmp[1], r, pin)
            }

            location?.let { loc ->
                toScreen(loc.latitude, loc.longitude)
                val x = tmp[0]; val y = tmp[1]
                if (loc.hasAccuracy()) {
                    val rad = (loc.accuracy * t.pagePerMeter(loc.latitude, loc.longitude) * scale).toFloat()
                    if (rad > 12 * density) canvas.drawCircle(x, y, rad, acc)
                }
                if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1f) {
                    val ang = Math.toRadians(loc.bearing.toDouble()) + t.northAngle(loc.latitude, loc.longitude)
                    val len = 40f * density
                    val half = Math.toRadians(26.0)
                    val path = Path().apply {
                        moveTo(x, y)
                        lineTo(x + (len * sin(ang - half)).toFloat(), y - (len * cos(ang - half)).toFloat())
                        lineTo(x + (len * sin(ang + half)).toFloat(), y - (len * cos(ang + half)).toFloat())
                        close()
                    }
                    canvas.drawPath(path, cone)
                }
                if (!follow) canvas.drawLine(x, y, area().exactCenterX(), area().exactCenterY(), guide)
                canvas.drawCircle(x, y, 12f * density, white)
                canvas.drawCircle(x, y, 9f * density, dot)
            }
            if (!follow) drawCrosshair(canvas)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    /** Mira central cuando el plano se desplaza a mano. */
    private fun drawCrosshair(canvas: Canvas) {
        val cx = area().exactCenterX(); val cy = area().exactCenterY()
        val r = 28f * density
        val tick = 10f * density
        for (p in arrayOf(crossHalo, cross)) {
            canvas.drawCircle(cx, cy, r, p)
            canvas.drawLine(cx, cy - r - tick * 0.35f, cx, cy - r + tick, p)
            canvas.drawLine(cx, cy + r + tick * 0.35f, cx, cy + r - tick, p)
            canvas.drawLine(cx - r - tick * 0.35f, cy, cx - r + tick, cy, p)
            canvas.drawLine(cx + r + tick * 0.35f, cy, cx + r - tick, cy, p)
        }
        canvas.drawCircle(cx, cy, 4.5f * density, white)
        canvas.drawCircle(cx, cy, 3f * density, Paint(cross).apply { style = Paint.Style.FILL })
    }

    /** [lat, lon] bajo la mira, o null si se está siguiendo la posición. */
    fun crosshairGeo(): DoubleArray? {
        if (follow || scale == 0f) return null
        return map?.transform?.toGeo(cx.toDouble(), cy.toDouble())
    }

    // ---------- detalle en alta resolución ----------

    private val detailRunnable = Runnable { renderDetail() }

    private fun scheduleDetail() {
        handler.removeCallbacks(detailRunnable)
        handler.postDelayed(detailRunnable, 300)
    }

    private fun renderDetail() {
        val e = map ?: return
        val b = base ?: return
        val baseScale = b.width / e.pageWidth.toFloat()
        detailJob?.cancel()
        if (scale <= baseScale * 1.15f) { if (detail != null) { detail = null; draw() }; return }
        buildMatrix()
        val inv = Matrix().also { m.invert(it) }
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inv.mapRect(r)
        if (!r.intersect(0f, 0f, e.pageWidth.toFloat(), e.pageHeight.toFloat())) return
        val outW = (r.width() * scale).toInt().coerceIn(1, maxOf(1, width))
        val outH = (r.height() * scale).toInt().coerceIn(1, maxOf(1, height))
        detailJob = scope.launch {
            val bmp = runCatching { PdfRenderPool.renderRegion(MapStore.pdfFile(e), r, outW, outH) }.getOrNull() ?: return@launch
            if (map?.id != e.id) return@launch
            detail = bmp
            detailRect.set(r)
            draw()
        }
    }

    companion object {
        private const val TAG = "CarMapRenderer"
    }
}
