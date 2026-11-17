package cl.geoplano.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cl.geoplano.geo.GeoPdfParser
import cl.geoplano.render.PdfRenderPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * Biblioteca local de planos, marcadores y tracks. Todo queda en el almacenamiento
 * interno de la app y funciona sin conexión.
 */
object MapStore {

    private lateinit var root: File
    private val lock = Any()

    private val _maps = MutableStateFlow<List<MapEntry>>(emptyList())
    val maps: StateFlow<List<MapEntry>> = _maps

    private val _placemarks = MutableStateFlow<List<Placemark>>(emptyList())
    val placemarks: StateFlow<List<Placemark>> = _placemarks

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    /** Plano elegido por última vez (lo usa también Android Auto). */
    private val _selectedMapId = MutableStateFlow<String?>(null)
    val selectedMapId: StateFlow<String?> = _selectedMapId

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        if (::root.isInitialized) return
        root = context.filesDir
        File(root, "maps").mkdirs()
        File(root, "thumbs").mkdirs()
        File(root, "tracks").mkdirs()
        prefs = context.getSharedPreferences("geoplano", Context.MODE_PRIVATE)
        _maps.value = readArray(File(root, "library.json")).map { MapEntry.fromJson(it) }
        _placemarks.value = readArray(File(root, "placemarks.json")).map { Placemark.fromJson(it) }
        _tracks.value = File(root, "tracks").listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { Track.fromJson(org.json.JSONObject(it.readText())) }.getOrNull() }
            .sortedBy { it.points.firstOrNull()?.time ?: 0 }
        _selectedMapId.value = prefs.getString("selectedMap", null)
    }

    fun pdfFile(m: MapEntry) = File(File(root, "maps"), m.fileName)
    fun thumbFile(m: MapEntry) = File(File(root, "thumbs"), "${m.id}.png")
    fun getMap(id: String?): MapEntry? = _maps.value.firstOrNull { it.id == id }

    fun select(id: String?) {
        _selectedMapId.value = id
        prefs.edit().putString("selectedMap", id).apply()
    }

    /** Copia el PDF, lee su georreferenciación y genera la miniatura. */
    suspend fun importPdf(context: Context, uri: Uri): MapEntry = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val displayName = queryName(context, uri) ?: "Plano"
        val fileName = "$id.pdf"
        val dest = File(File(root, "maps"), fileName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "No se pudo abrir el archivo" }
            dest.outputStream().use { input.copyTo(it) }
        }
        val geo = try {
            GeoPdfParser.parse(dest.readBytes())
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
        val size = PdfRenderPool.pageSize(dest)
        val entry = MapEntry(
            id = id,
            name = displayName.removeSuffix(".pdf").removeSuffix(".PDF"),
            fileName = fileName,
            pageWidth = size?.first ?: geo?.pageWidth ?: 612.0,
            pageHeight = size?.second ?: geo?.pageHeight ?: 792.0,
            controlPoints = geo?.controlPoints.orEmpty(),
            neatline = geo?.neatline,
            projection = geo?.projectionName,
            importedAt = System.currentTimeMillis(),
            sizeBytes = dest.length()
        )
        runCatching { PdfRenderPool.writeThumbnail(dest, thumbFile(entry), 360) }
        synchronized(lock) {
            _maps.value = _maps.value + entry
            saveMaps()
        }
        entry
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        _maps.value = _maps.value.map { if (it.id == id) it.copy(name = name) else it }
        saveMaps()
    }

    fun deleteMap(id: String) {
        synchronized(lock) {
            val m = getMap(id) ?: return
            pdfFile(m).delete(); thumbFile(m).delete()
            _maps.value = _maps.value.filterNot { it.id == id }
            saveMaps()
        }
        if (_selectedMapId.value == id) select(null)
    }

    fun addPlacemark(p: Placemark) = synchronized(lock) {
        _placemarks.value = _placemarks.value + p
        savePlacemarks()
    }

    fun updatePlacemark(p: Placemark) = synchronized(lock) {
        _placemarks.value = _placemarks.value.map { if (it.id == p.id) p else it }
        savePlacemarks()
    }

    fun deletePlacemark(id: String) = synchronized(lock) {
        _placemarks.value = _placemarks.value.filterNot { it.id == id }
        savePlacemarks()
    }

    fun saveTrack(t: Track) = synchronized(lock) {
        File(File(root, "tracks"), "${t.id}.json").writeText(t.toJson().toString())
        _tracks.value = _tracks.value.filterNot { it.id == t.id } + t
    }

    fun deleteTrack(id: String) = synchronized(lock) {
        File(File(root, "tracks"), "$id.json").delete()
        _tracks.value = _tracks.value.filterNot { it.id == id }
    }

    private fun saveMaps() = writeAtomic(File(root, "library.json"), JSONArray().apply { _maps.value.forEach { put(it.toJson()) } }.toString())
    private fun savePlacemarks() = writeAtomic(File(root, "placemarks.json"), JSONArray().apply { _placemarks.value.forEach { put(it.toJson()) } }.toString())

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun readArray(f: File): List<org.json.JSONObject> {
        if (!f.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
