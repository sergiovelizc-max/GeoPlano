package cl.geoplano.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import cl.geoplano.data.MapEntry
import cl.geoplano.data.Placemark
import cl.geoplano.data.Track
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Visor del plano: pan/zoom con gestos, bitmap base de la página completa + bitmap de detalle
 * de la zona visible, y capas de ubicación, marcadores, tracks y medición.
 */
class MapCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var map: MapEntry? = null
        private set
    private var base: Bitmap? = null
    private var detail: Bitmap? = null
    private val detailRect = RectF()

    /** página (pt) → pantalla (px) */
    private val pageMatrix = Matrix()
    private val inverse = Matrix()
    private var fitted = false
    private var minScale = 0.01f
    private var maxScale = 100f

    var location: Location? = null
        set(v) { field = v; invalidate() }
    var placemarks: List<Placemark> = emptyList()
        set(v) { field = v; invalidate() }
    var tracks: List<Track> = emptyList()
        set(v) { field = v; invalidate() }
    var activeTrack: Track? = null
        set(v) { field = v; invalidate() }
    var measure: List<DoubleArray> = emptyList()
        set(v) { field = v; invalidate() }

    /** Mira central: se muestra al desplazarse por el plano (no en modo seguir). */
    var crosshair: Boolean = false
        set(v) { field = v; lastCenter = null; invalidate() }
    /** Llamado cuando cambia el punto geográfico bajo la mira. */
    var onCenterChanged: ((lat: Double, lon: Double) -> Unit)? = null
    private var lastCenter: DoubleArray? = null

    /** Llamado (con retardo) cuando cambia la zona visible: rect de página visible y escala px/pt. */
    var onViewportChanged: ((RectF, Float) -> Unit)? = null
    var onLongPressGeo: ((lat: Double, lon: Double) -> Unit)? = null
    var onTapGeo: ((lat: Double, lon: Double) -> Boolean)? = null
    var onPlacemarkTap: ((Placemark) -> Unit)? = null
    var onUserMoved: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val accFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 30, 136, 229) }
    private val accStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 30, 136, 229); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 136, 229) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 30, 136, 229) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 142, 61); style = Paint.Style.STROKE; strokeWidth = 4f * density
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val activeTrackPaint = Paint(trackPaint).apply { color = Color.rgb(211, 47, 47) }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(156, 39, 176); style = Paint.Style.STROKE; strokeWidth = 3f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f * density, 6f * density), 0f)
    }
    private val measureFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 156, 39, 176) }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 12f * density; isFakeBoldText = true
    }
    private val labelHalo = Paint(labelPaint).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val pts = FloatArray(2)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 110, 110); style = Paint.Style.STROKE; strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val crossHalo = Paint(crossPaint).apply { color = Color.argb(170, 255, 255, 255); strokeWidth = 7f * density }
    private val crossDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 110, 110) }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 30, 136, 229); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
    }

    fun setMap(m: MapEntry, baseBitmap: Bitmap) {
        map = m
        base = baseBitmap
        detail = null
        fitted = false
        if (width > 0) fit()
        invalidate()
    }

    fun setDetail(bmp: Bitmap?, rect: RectF?) {
        detail = bmp
        if (rect != null) detailRect.set(rect)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!fitted) fit() else { clamp(); notifyViewport() }
    }

    private fun fit() {
        val m = map ?: return
        if (width == 0 || height == 0) return
        val n = m.neatline ?: doubleArrayOf(0.0, 0.0, m.pageWidth, m.pageHeight)
        val target = RectF(n[0].toFloat(), n[1].toFloat(), n[2].toFloat(), n[3].toFloat())
        val s = min(width / target.width(), height / target.height())
        minScale = min(width / m.pageWidth.toFloat(), height / m.pageHeight.toFloat()) * 0.5f
        maxScale = s * 60f
        pageMatrix.reset()
        pageMatrix.postTranslate(-target.centerX(), -target.centerY())
        pageMatrix.postScale(s, s)
        pageMatrix.postTranslate(width / 2f, height / 2f)
        fitted = true
        notifyViewport()
        invalidate()
    }

    fun scale(): Float {
        val v = FloatArray(9); pageMatrix.getValues(v); return v[Matrix.MSCALE_X]
    }

    /** Centra el plano en una coordenada geográfica. */
    fun centerOn(lat: Double, lon: Double, zoomIn: Boolean = false) {
        val t = map?.transform ?: return
        val p = t.toPage(lat, lon)
        pts[0] = p[0].toFloat(); pts[1] = p[1].toFloat()
        if (zoomIn) {
            val target = max(scale(), min(maxScale, (map!!.pageWidth.toFloat().let { width / it }) * 4f))
            val f = target / scale()
            pageMatrix.postScale(f, f, width / 2f, height / 2f)
        }
        pageMatrix.mapPoints(pts)
        pageMatrix.postTranslate(width / 2f - pts[0], height / 2f - pts[1])
        notifyViewport()
        invalidate()
    }

    fun zoomBy(f: Float) {
        val target = (scale() * f).coerceIn(minScale, maxScale)
        val k = target / scale()
        pageMatrix.postScale(k, k, width / 2f, height / 2f)
        clamp(); notifyViewport(); invalidate()
    }

    // ---------- gestos ----------

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale() * d.scaleFactor).coerceIn(minScale, maxScale)
            val k = target / scale()
            pageMatrix.postScale(k, k, d.focusX, d.focusY)
            onUserMoved?.invoke()
            clamp(); invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            pageMatrix.postTranslate(-dx, -dy)
            onUserMoved?.invoke()
            clamp(); invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = (scale() * 2f).coerceIn(minScale, maxScale)
            val k = target / scale()
            pageMatrix.postScale(k, k, e.x, e.y)
            onUserMoved?.invoke()
            clamp(); notifyViewport(); invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val geo = screenToGeo(e.x, e.y) ?: return false
            if (onTapGeo?.invoke(geo[0], geo[1]) == true) return true
            hitPlacemark(e.x, e.y)?.let { onPlacemarkTap?.invoke(it); return true }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            val geo = screenToGeo(e.x, e.y) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPressGeo?.invoke(geo[0], geo[1])
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            notifyViewport()
        }
        return true
    }

    /** Mantiene al menos parte de la página en pantalla. */
    private fun clamp() {
        val m = map ?: return
        val r = RectF(0f, 0f, m.pageWidth.toFloat(), m.pageHeight.toFloat())
        pageMatrix.mapRect(r)
        val margin = 0.25f
        var dx = 0f; var dy = 0f
        if (r.right < width * margin) dx = width * margin - r.right
        if (r.left > width * (1 - margin)) dx = width * (1 - margin) - r.left
        if (r.bottom < height * margin) dy = height * margin - r.bottom
        if (r.top > height * (1 - margin)) dy = height * (1 - margin) - r.top
        if (dx != 0f || dy != 0f) pageMatrix.postTranslate(dx, dy)
    }

    private val viewportRunnable = Runnable {
        val m = map ?: return@Runnable
        if (width == 0) return@Runnable
        pageMatrix.invert(inverse)
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(r)
        if (!r.intersect(0f, 0f, m.pageWidth.toFloat(), m.pageHeight.toFloat())) return@Runnable
        onViewportChanged?.invoke(r, scale())
    }

    private fun notifyViewport() {
        removeCallbacks(viewportRunnable)
        postDelayed(viewportRunnable, 220)
    }

    fun screenToGeo(x: Float, y: Float): DoubleArray? {
        val t = map?.transform ?: return null
        pageMatrix.invert(inverse)
        pts[0] = x; pts[1] = y
        inverse.mapPoints(pts)
        return t.toGeo(pts[0].toDouble(), pts[1].toDouble())
    }

    private fun geoToScreen(lat: Double, lon: Double, out: FloatArray): Boolean {
        val t = map?.transform ?: return false
        val p = t.toPage(lat, lon)
        out[0] = p[0].toFloat(); out[1] = p[1].toFloat()
        pageMatrix.mapPoints(out)
        return true
    }

    private fun hitPlacemark(x: Float, y: Float): Placemark? {
        val tmp = FloatArray(2)
        val r = 22f * density
        return placemarks.lastOrNull { p ->
            geoToScreen(p.lat, p.lon, tmp) && (tmp[0] - x) * (tmp[0] - x) + (tmp[1] - 10 * density - y).let { it * it } < r * r
        }
    }

    // ---------- dibujo ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = map ?: return
        val b = base ?: return
        canvas.save()
        canvas.concat(pageMatrix)
        canvas.drawRect(0f, 0f, m.pageWidth.toFloat(), m.pageHeight.toFloat(), white)
        canvas.drawBitmap(b, null, RectF(0f, 0f, m.pageWidth.toFloat(), m.pageHeight.toFloat()), bmpPaint)
        detail?.let { if (!it.isRecycled) canvas.drawBitmap(it, null, detailRect, bmpPaint) }
        canvas.restore()

        if (m.transform == null) return
        tracks.forEach { drawTrack(canvas, it, trackPaint) }
        activeTrack?.let { drawTrack(canvas, it, activeTrackPaint) }
        drawMeasure(canvas)
        placemarks.forEach { drawPin(canvas, it) }
        if (crosshair) drawGuideLine(canvas)
        drawLocation(canvas)
        if (crosshair) {
            drawCrosshair(canvas)
            screenToGeo(width / 2f, height / 2f)?.let { c ->
                val prev = lastCenter
                if (prev == null || prev[0] != c[0] || prev[1] != c[1]) {
                    lastCenter = c
                    post { onCenterChanged?.invoke(c[0], c[1]) }
                }
            }
        }
    }

    /** Línea punteada desde tu posición hasta la mira. */
    private fun drawGuideLine(canvas: Canvas) {
        val loc = location ?: return
        val tmp = FloatArray(2)
        if (!geoToScreen(loc.latitude, loc.longitude, tmp)) return
        canvas.drawLine(tmp[0], tmp[1], width / 2f, height / 2f, guidePaint)
    }

    /** Mira: círculo con cuatro marcas hacia el centro y un punto central. */
    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = 30f * density
        val tick = 11f * density
        for (p in arrayOf(crossHalo, crossPaint)) {
            canvas.drawCircle(cx, cy, r, p)
            canvas.drawLine(cx, cy - r - tick * 0.35f, cx, cy - r + tick, p)
            canvas.drawLine(cx, cy + r + tick * 0.35f, cx, cy + r - tick, p)
            canvas.drawLine(cx - r - tick * 0.35f, cy, cx - r + tick, cy, p)
            canvas.drawLine(cx + r + tick * 0.35f, cy, cx + r - tick, cy, p)
        }
        canvas.drawCircle(cx, cy, 4.5f * density, white)
        canvas.drawCircle(cx, cy, 3f * density, crossDot)
    }

    private fun drawTrack(canvas: Canvas, t: Track, paint: Paint) {
        if (t.points.size < 2) return
        val path = Path()
        val tmp = FloatArray(2)
        t.points.forEachIndexed { i, p ->
            geoToScreen(p.lat, p.lon, tmp)
            if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMeasure(canvas: Canvas) {
        if (measure.isEmpty()) return
        val path = Path()
        val tmp = FloatArray(2)
        measure.forEachIndexed { i, p ->
            geoToScreen(p[0], p[1], tmp)
            if (i == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1])
        }
        if (measure.size >= 3) {
            val closed = Path(path).apply { close() }
            canvas.drawPath(closed, measureFill)
        }
        canvas.drawPath(path, measurePaint)
        measure.forEach { p ->
            geoToScreen(p[0], p[1], tmp)
            canvas.drawCircle(tmp[0], tmp[1], 6f * density, white)
            canvas.drawCircle(tmp[0], tmp[1], 4f * density, Paint(measurePaint).apply { style = Paint.Style.FILL; pathEffect = null })
        }
    }

    private fun drawPin(canvas: Canvas, p: Placemark) {
        val tmp = FloatArray(2)
        if (!geoToScreen(p.lat, p.lon, tmp)) return
        val x = tmp[0]; val y = tmp[1]
        if (x < -50 || y < -50 || x > width + 50 || y > height + 50) return
        val r = 9f * density
        pinPaint.color = p.color
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x - r * 0.75f, y - r * 1.4f)
            lineTo(x + r * 0.75f, y - r * 1.4f)
            close()
        }
        canvas.drawCircle(x, y - r * 1.8f, r + 2 * density, white)
        canvas.drawPath(path, pinPaint)
        canvas.drawCircle(x, y - r * 1.8f, r, pinPaint)
        canvas.drawCircle(x, y - r * 1.8f, r * 0.38f, white)
        if (p.name.isNotBlank() && scale() > minScale * 2) {
            canvas.drawText(p.name, x + r * 1.3f, y - r * 1.5f, labelHalo)
            canvas.drawText(p.name, x + r * 1.3f, y - r * 1.5f, labelPaint)
        }
    }

    private fun drawLocation(canvas: Canvas) {
        val loc = location ?: return
        val t = map?.transform ?: return
        val tmp = FloatArray(2)
        geoToScreen(loc.latitude, loc.longitude, tmp)
        val x = tmp[0]; val y = tmp[1]
        if (loc.hasAccuracy()) {
            val rad = (loc.accuracy * t.pagePerMeter(loc.latitude, loc.longitude) * scale()).toFloat()
            if (rad > 10 * density) {
                canvas.drawCircle(x, y, rad, accFill)
                canvas.drawCircle(x, y, rad, accStroke)
            }
        }
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.8f) {
            // dirección de avance, corregida por la orientación del norte en el plano
            val ang = Math.toRadians(loc.bearing.toDouble()) + t.northAngle(loc.latitude, loc.longitude)
            val len = 34f * density
            val half = Math.toRadians(28.0)
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + (len * sin(ang - half)).toFloat(), y - (len * cos(ang - half)).toFloat())
                lineTo(x + (len * sin(ang + half)).toFloat(), y - (len * cos(ang + half)).toFloat())
                close()
            }
            canvas.drawPath(path, cone)
        }
        canvas.drawCircle(x, y, 10f * density, white)
        canvas.drawCircle(x, y, 7f * density, dotFill)
    }
}
