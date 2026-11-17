package cl.geoplano.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cl.geoplano.R
import cl.geoplano.data.MapEntry
import cl.geoplano.data.MapStore
import cl.geoplano.data.Placemark
import cl.geoplano.export.KmlExporter
import cl.geoplano.geo.GeoMath
import cl.geoplano.location.LocationRepository
import cl.geoplano.render.PdfRenderPool
import cl.geoplano.track.TrackRecorder
import cl.geoplano.track.TrackingService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MapActivity : AppCompatActivity() {

    private lateinit var entry: MapEntry
    private lateinit var mapView: MapCanvasView
    private lateinit var info: TextView
    private lateinit var fabLocate: FloatingActionButton
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var measurePanel: MaterialCardView
    private lateinit var measureText: TextView

    private var follow = false
    private var measuring = false
    private val measurePts = ArrayList<DoubleArray>()
    private var detailJob: Job? = null
    private var baseWidthPx = 0

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startTrack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapStore.init(this)
        val m = MapStore.getMap(intent.getStringExtra(EXTRA_ID))
        if (m == null) { finish(); return }
        entry = m
        MapStore.select(m.id)
        setContentView(R.layout.activity_map)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mapView = findViewById(R.id.mapView)
        info = findViewById(R.id.info)
        fabLocate = findViewById(R.id.fabLocate)
        fabRecord = findViewById(R.id.fabRecord)
        measurePanel = findViewById(R.id.measurePanel)
        measureText = findViewById(R.id.measureText)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = m.name
        toolbar.subtitle = if (m.isGeoreferenced) (m.projection ?: "Georreferenciado") else "Sin georreferencia: sólo visualización"
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.map_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_measure -> toggleMeasure()
                R.id.action_placemarks -> showPlacemarks()
                R.id.action_tracks -> showTracks()
                R.id.action_export -> export()
                R.id.action_info -> showInfo()
            }
            true
        }

        fabLocate.setOnClickListener {
            val loc = LocationRepository.location.value
            if (loc == null) {
                Toast.makeText(this, "Esperando señal GPS…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!entry.contains(loc.latitude, loc.longitude)) {
                Toast.makeText(this, "Estás fuera de este plano", Toast.LENGTH_SHORT).show()
            }
            follow = true
            updateFollowIcon()
            mapView.centerOn(loc.latitude, loc.longitude, zoomIn = true)
        }
        findViewById<FloatingActionButton>(R.id.fabPin).setOnClickListener {
            val loc = LocationRepository.location.value
            if (loc == null) Toast.makeText(this, "Sin ubicación GPS aún. Mantén presionado el plano para marcar un punto.", Toast.LENGTH_LONG).show()
            else editPlacemark(null, loc.latitude, loc.longitude)
        }
        fabRecord.setOnClickListener {
            if (TrackRecorder.isRecording) {
                TrackingService.stop(this)
                Toast.makeText(this, "Track guardado", Toast.LENGTH_SHORT).show()
            } else if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startTrack()
        }
        findViewById<Button>(R.id.measureUndo).setOnClickListener {
            if (measurePts.isNotEmpty()) measurePts.removeAt(measurePts.size - 1)
            updateMeasure()
        }
        findViewById<Button>(R.id.measureClose).setOnClickListener { toggleMeasure() }

        mapView.onUserMoved = { if (follow) { follow = false; updateFollowIcon() } }
        mapView.onLongPressGeo = { lat, lon -> if (!measuring) editPlacemark(null, lat, lon) }
        mapView.onTapGeo = { lat, lon ->
            if (measuring) { measurePts.add(doubleArrayOf(lat, lon)); updateMeasure(); true } else false
        }
        mapView.onPlacemarkTap = { p -> editPlacemark(p, p.lat, p.lon) }
        mapView.onViewportChanged = { rect, scale -> requestDetail(rect, scale) }

        if (!m.isGeoreferenced) {
            info.text = "Este PDF no trae georreferencia. Se puede ver, pero sin GPS."
            fabLocate.visibility = View.GONE
            fabRecord.visibility = View.GONE
            findViewById<View>(R.id.fabPin).visibility = View.GONE
        }

        lifecycleScope.launch {
            val maxPx = if (Runtime.getRuntime().maxMemory() >= 384L * 1024 * 1024) 4096 else 2560
            val bmp = try {
                PdfRenderPool.renderFull(MapStore.pdfFile(m), maxPx)
            } catch (e: Throwable) {
                Toast.makeText(this@MapActivity, "No se pudo abrir el PDF: ${e.message}", Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            baseWidthPx = bmp.width
            mapView.setMap(m, bmp)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LocationRepository.location.collect { onLocation(it) } }
                launch { MapStore.placemarks.collect { list -> mapView.placemarks = list.filter { it.mapId == null || it.mapId == m.id || m.contains(it.lat, it.lon) } } }
                launch { MapStore.tracks.collect { list -> mapView.tracks = list.filter { it.mapId == m.id || it.points.any { p -> m.contains(p.lat, p.lon) } } } }
                launch {
                    TrackRecorder.active.collect { t ->
                        mapView.activeTrack = t
                        fabRecord.setImageResource(if (t != null) R.drawable.ic_stop else R.drawable.ic_record)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::entry.isInitialized && entry.isGeoreferenced) LocationRepository.acquire(this)
    }

    override fun onStop() {
        if (::entry.isInitialized && entry.isGeoreferenced) LocationRepository.release()
        super.onStop()
    }

    private fun onLocation(loc: android.location.Location?) {
        mapView.location = loc
        if (!entry.isGeoreferenced) return
        if (loc == null) {
            info.text = if (LocationRepository.hasPermission(this)) "Buscando GPS…" else "Sin permiso de ubicación"
            return
        }
        val inside = entry.contains(loc.latitude, loc.longitude)
        val sb = StringBuilder(GeoMath.formatLatLon(loc.latitude, loc.longitude))
        if (loc.hasAccuracy()) sb.append("  ±").append(loc.accuracy.toInt()).append(" m")
        if (loc.hasAltitude()) sb.append("  ").append(loc.altitude.toInt()).append(" msnm")
        if (!inside) {
            val c = entry.center()
            if (c != null) sb.append("\nFuera del plano · centro a ").append(GeoMath.formatDistance(GeoMath.distance(loc.latitude, loc.longitude, c[0], c[1])))
        }
        TrackRecorder.active.value?.let { t ->
            sb.append("\n● Grabando: ").append(GeoMath.formatDistance(GeoMath.pathLength(t.points.map { doubleArrayOf(it.lat, it.lon) })))
        }
        info.text = sb
        if (follow) mapView.centerOn(loc.latitude, loc.longitude)
    }

    private fun updateFollowIcon() {
        fabLocate.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (follow) ContextCompat.getColor(this, R.color.location) else ContextCompat.getColor(this, R.color.brand)
        )
    }

    /** Re-renderiza la zona visible en alta resolución cuando el zoom supera la resolución del bitmap base. */
    private fun requestDetail(rect: RectF, scale: Float) {
        detailJob?.cancel()
        val baseScale = baseWidthPx / entry.pageWidth.toFloat()
        if (baseWidthPx == 0 || scale <= baseScale * 1.15f) {
            mapView.setDetail(null, null)
            return
        }
        val outW = (rect.width() * scale).toInt().coerceIn(1, mapView.width.coerceAtLeast(1))
        val outH = (rect.height() * scale).toInt().coerceIn(1, mapView.height.coerceAtLeast(1))
        val file = MapStore.pdfFile(entry)
        detailJob = lifecycleScope.launch {
            val bmp = runCatching { PdfRenderPool.renderRegion(file, rect, outW, outH) }.getOrNull() ?: return@launch
            mapView.setDetail(bmp, rect)
        }
    }

    private fun startTrack() {
        if (!LocationRepository.hasPermission(this)) {
            Toast.makeText(this, "Necesitas conceder el permiso de ubicación", Toast.LENGTH_LONG).show(); return
        }
        if (TrackingService.start(this, entry.id)) Toast.makeText(this, "Grabando track", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Android no permitió iniciar la grabación", Toast.LENGTH_LONG).show()
    }

    // ---------- medición ----------

    private fun toggleMeasure() {
        if (!entry.isGeoreferenced) return
        measuring = !measuring
        measurePts.clear()
        measurePanel.visibility = if (measuring) View.VISIBLE else View.GONE
        updateMeasure()
    }

    private fun updateMeasure() {
        mapView.measure = ArrayList(measurePts)
        measureText.text = when {
            measurePts.size < 2 -> "Toca el plano para agregar puntos"
            measurePts.size == 2 -> "Distancia: " + GeoMath.formatDistance(GeoMath.pathLength(measurePts))
            else -> "Recorrido: " + GeoMath.formatDistance(GeoMath.pathLength(measurePts)) +
                "\nPerímetro: " + GeoMath.formatDistance(GeoMath.pathLength(measurePts + listOf(measurePts.first()))) +
                "   Área: " + GeoMath.formatArea(GeoMath.polygonArea(measurePts))
        }
    }

    // ---------- marcadores ----------

    private fun editPlacemark(existing: Placemark?, lat: Double, lon: Double) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val name = EditText(this).apply {
            hint = "Nombre"; setText(existing?.name ?: "Punto ${MapStore.placemarks.value.size + 1}")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val note = EditText(this).apply {
            hint = "Nota (ej.: daño observado, especie, foco)"; setText(existing?.note ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val coords = TextView(this).apply { text = GeoMath.formatLatLon(lat, lon); setPadding(8, 8, 8, 0) }
        box.addView(name); box.addView(note); box.addView(coords)
        val colors = intArrayOf(0xFFE53935.toInt(), 0xFFE8A33D.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt())
        var color = existing?.color ?: colors[0]
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 0) }
        val swatches = colors.map { c ->
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(84, 84).apply { marginEnd = 20 }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c)
                }
            }
        }
        fun refresh() = swatches.forEachIndexed { i, v -> v.alpha = if (colors[i] == color) 1f else 0.35f }
        swatches.forEachIndexed { i, v -> v.setOnClickListener { color = colors[i]; refresh() }; row.addView(v) }
        refresh()
        box.addView(row)

        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Nuevo marcador" else "Marcador")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val p = Placemark(
                    existing?.id ?: UUID.randomUUID().toString(), entry.id,
                    name.text.toString().trim(), note.text.toString().trim(), lat, lon,
                    existing?.time ?: System.currentTimeMillis(), color
                )
                if (existing == null) MapStore.addPlacemark(p) else MapStore.updatePlacemark(p)
            }
            .setNegativeButton("Cancelar", null)
        if (existing != null) {
            b.setNeutralButton("Eliminar") { _, _ -> MapStore.deletePlacemark(existing.id) }
        }
        b.show()
    }

    private fun showPlacemarks() {
        val list = mapView.placemarks
        if (list.isEmpty()) { Toast.makeText(this, "Sin marcadores en este plano", Toast.LENGTH_SHORT).show(); return }
        val loc = LocationRepository.location.value
        val labels = list.map { p ->
            val d = loc?.let { " · " + GeoMath.formatDistance(GeoMath.distance(it.latitude, it.longitude, p.lat, p.lon)) } ?: ""
            p.name + d
        }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Marcadores")
            .setItems(labels) { _, i ->
                follow = false; updateFollowIcon()
                mapView.centerOn(list[i].lat, list[i].lon, zoomIn = true)
            }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun showTracks() {
        val list = mapView.tracks
        if (list.isEmpty()) { Toast.makeText(this, "Sin tracks en este plano", Toast.LENGTH_SHORT).show(); return }
        val df = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
        val labels = list.map { t ->
            t.name + " · " + GeoMath.formatDistance(GeoMath.pathLength(t.points.map { doubleArrayOf(it.lat, it.lon) })) +
                (t.points.firstOrNull()?.let { " · " + df.format(Date(it.time)) } ?: "")
        }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Tracks")
            .setItems(labels) { _, i ->
                val t = list[i]
                MaterialAlertDialogBuilder(this).setTitle(t.name)
                    .setPositiveButton("Ver") { _, _ ->
                        t.points.firstOrNull()?.let { follow = false; updateFollowIcon(); mapView.centerOn(it.lat, it.lon, true) }
                    }
                    .setNeutralButton("Eliminar") { _, _ -> MapStore.deleteTrack(t.id) }
                    .setNegativeButton("Cerrar", null).show()
            }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun export() {
        val pms = mapView.placemarks
        val trks = mapView.tracks
        if (pms.isEmpty() && trks.isEmpty()) {
            Toast.makeText(this, "No hay marcadores ni tracks para exportar", Toast.LENGTH_SHORT).show(); return
        }
        KmlExporter.share(this, entry, pms, trks)
    }

    private fun showInfo() {
        val sb = StringBuilder()
        sb.append("Archivo: ").append(String.format(Locale.US, "%.1f MB", entry.sizeBytes / 1_048_576.0)).append('\n')
        sb.append("Página: ").append(entry.pageWidth.toInt()).append(" × ").append(entry.pageHeight.toInt()).append(" pt\n")
        if (entry.isGeoreferenced) {
            sb.append("Sistema: ").append(entry.projection ?: "—").append('\n')
            sb.append("Puntos de control: ").append(entry.controlPoints.size).append('\n')
            entry.center()?.let { sb.append("Centro: ").append(GeoMath.formatLatLon(it[0], it[1])) }
        } else sb.append("Sin georreferencia. Exporta el plano desde ArcGIS Pro marcando “Exportar información de georreferencia”.")
        MaterialAlertDialogBuilder(this).setTitle(entry.name).setMessage(sb).setPositiveButton("OK", null).show()
    }

    companion object {
        private const val EXTRA_ID = "mapId"
        fun open(context: Context, id: String) =
            context.startActivity(Intent(context, MapActivity::class.java).putExtra(EXTRA_ID, id))
    }
}
