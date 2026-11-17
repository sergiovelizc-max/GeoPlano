package cl.geoplano.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.geoplano.R
import cl.geoplano.data.MapEntry
import cl.geoplano.data.MapStore
import cl.geoplano.geo.GeoMath
import cl.geoplano.location.LocationRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val adapter = MapAdapter()
    private lateinit var empty: TextView
    private lateinit var fab: ExtendedFloatingActionButton

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationRepository.restart(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapStore.init(this)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.subtitle = "Planos PDF georreferenciados · sin conexión"
        empty = findViewById(R.id.empty)
        fab = findViewById(R.id.fabImport)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        fab.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }

        if (!LocationRepository.hasPermission(this)) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(MapStore.maps, LocationRepository.location) { maps, loc -> maps to loc }.collect { (maps, loc) ->
                    adapter.submit(sortMaps(maps, loc), loc)
                    empty.visibility = if (maps.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        LocationRepository.acquire(this)
    }

    override fun onStop() {
        LocationRepository.release()
        super.onStop()
    }

    /** PDF recibido con "Abrir con" o "Compartir" desde otra app. */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) {
            intent.action = null
            importUris(listOf(uri), openAfter = true)
        }
    }

    private fun importUris(uris: List<Uri>, openAfter: Boolean = false) {
        fab.isEnabled = false
        fab.text = "Importando…"
        lifecycleScope.launch {
            var last: MapEntry? = null
            var noGeo = 0
            for (u in uris) {
                try {
                    val e = MapStore.importPdf(this@MainActivity, u)
                    if (!e.isGeoreferenced) noGeo++
                    last = e
                } catch (ex: Exception) {
                    Toast.makeText(this@MainActivity, "Error al importar: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
            fab.isEnabled = true
            fab.text = getString(R.string.import_pdf)
            if (noGeo > 0) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("PDF sin georreferencia")
                    .setMessage(
                        "$noGeo de ${uris.size} archivo(s) no trae coordenadas. Se pueden ver, pero sin tu posición.\n\n" +
                            "En ArcGIS Pro: Compartir → Exportar diseño → PDF → marca “Exportar información de georreferencia”."
                    )
                    .setPositiveButton("Entendido", null).show()
            }
            if (openAfter) last?.let { MapActivity.open(this@MainActivity, it.id) }
        }
    }

    /** Primero los planos donde estás, luego por cercanía, luego por fecha de importación. */
    private fun sortMaps(maps: List<MapEntry>, loc: Location?): List<MapEntry> {
        if (loc == null) return maps.sortedByDescending { it.importedAt }
        return maps.sortedWith(compareBy<MapEntry>(
            { if (it.contains(loc.latitude, loc.longitude)) 0 else 1 },
            { m -> m.center()?.let { GeoMath.distance(loc.latitude, loc.longitude, it[0], it[1]) } ?: Double.MAX_VALUE }
        ))
    }

    private fun showOptions(m: MapEntry) {
        MaterialAlertDialogBuilder(this).setTitle(m.name)
            .setItems(arrayOf("Abrir", "Usar en Android Auto", "Renombrar", "Eliminar")) { _, which ->
                when (which) {
                    0 -> MapActivity.open(this, m.id)
                    1 -> { MapStore.select(m.id); Toast.makeText(this, "Se mostrará en Android Auto", Toast.LENGTH_SHORT).show() }
                    2 -> {
                        val input = EditText(this).apply { setText(m.name); setSelection(m.name.length) }
                        MaterialAlertDialogBuilder(this).setTitle("Renombrar").setView(input)
                            .setPositiveButton("Guardar") { _, _ -> MapStore.rename(m.id, input.text.toString().trim().ifEmpty { m.name }) }
                            .setNegativeButton("Cancelar", null).show()
                    }
                    3 -> MaterialAlertDialogBuilder(this).setTitle("¿Eliminar “${m.name}”?")
                        .setMessage("Se borra el PDF de la app. Los marcadores y tracks se conservan.")
                        .setPositiveButton("Eliminar") { _, _ -> MapStore.deleteMap(m.id) }
                        .setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    // ---------- lista ----------

    private inner class MapAdapter : RecyclerView.Adapter<MapAdapter.VH>() {
        private var items: List<MapEntry> = emptyList()
        private var loc: Location? = null
        private val thumbs = HashMap<String, android.graphics.Bitmap?>()

        fun submit(list: List<MapEntry>, location: Location?) {
            items = list; loc = location
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val status: TextView = v.findViewById(R.id.status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_map, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = items[position]
            h.name.text = m.name
            val size = String.format(Locale.US, "%.1f MB", m.sizeBytes / 1_048_576.0)
            h.subtitle.text = if (m.isGeoreferenced) "${m.projection ?: "Georreferenciado"} · $size" else "Sin georreferencia · $size"
            val bmp = thumbs.getOrPut(m.id) {
                MapStore.thumbFile(m).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            if (bmp != null) h.thumb.setImageBitmap(bmp) else h.thumb.setImageResource(R.drawable.ic_map)

            val l = loc
            when {
                !m.isGeoreferenced -> setStatus(h.status, "Solo visualización", Color.rgb(120, 120, 120), Color.rgb(235, 235, 235))
                l == null -> setStatus(h.status, "Esperando GPS", Color.rgb(120, 120, 120), Color.rgb(235, 235, 235))
                m.contains(l.latitude, l.longitude) -> setStatus(h.status, "● Estás aquí", Color.rgb(13, 71, 161), Color.rgb(219, 234, 254))
                else -> {
                    val d = m.center()?.let { GeoMath.distance(l.latitude, l.longitude, it[0], it[1]) }
                    setStatus(h.status, "a " + (d?.let { GeoMath.formatDistance(it) } ?: "—"), Color.rgb(90, 90, 90), Color.rgb(240, 238, 230))
                }
            }
            h.itemView.setOnClickListener { MapActivity.open(this@MainActivity, m.id) }
            h.itemView.setOnLongClickListener { showOptions(m); true }
        }

        private fun setStatus(t: TextView, text: String, fg: Int, bg: Int) {
            t.text = text
            t.setTextColor(fg)
            t.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 40f; setColor(bg) }
        }
    }
}
