package cl.geoplano.data

import cl.geoplano.geo.ControlPoint
import cl.geoplano.geo.GeoTransform
import org.json.JSONArray
import org.json.JSONObject

data class MapEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val pageWidth: Double,
    val pageHeight: Double,
    val controlPoints: List<ControlPoint>,
    /** Marco del mapa en página: x1, y1, x2, y2 (pt). */
    val neatline: DoubleArray?,
    val projection: String?,
    val importedAt: Long,
    val sizeBytes: Long
) {
    val isGeoreferenced: Boolean get() = controlPoints.size >= 3

    @Transient
    private var cachedTransform: GeoTransform? = null

    val transform: GeoTransform?
        get() {
            if (cachedTransform == null && isGeoreferenced) cachedTransform = GeoTransform.fromControlPoints(controlPoints)
            return cachedTransform
        }

    /** ¿La coordenada cae dentro del marco georreferenciado del plano? */
    fun contains(lat: Double, lon: Double): Boolean {
        val t = transform ?: return false
        val p = t.toPage(lat, lon)
        val n = neatline ?: doubleArrayOf(0.0, 0.0, pageWidth, pageHeight)
        return p[0] >= n[0] && p[0] <= n[2] && p[1] >= n[1] && p[1] <= n[3]
    }

    /** Centro geográfico aproximado [lat, lon]. */
    fun center(): DoubleArray? {
        val t = transform ?: return null
        val n = neatline ?: doubleArrayOf(0.0, 0.0, pageWidth, pageHeight)
        return t.toGeo((n[0] + n[2]) / 2, (n[1] + n[3]) / 2)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("fileName", fileName)
        put("pageWidth", pageWidth); put("pageHeight", pageHeight)
        put("cps", JSONArray().apply {
            controlPoints.forEach { put(JSONArray().put(it.lat).put(it.lon).put(it.x).put(it.y)) }
        })
        neatline?.let { n -> put("neatline", JSONArray().apply { n.forEach { put(it) } }) }
        projection?.let { put("projection", it) }
        put("importedAt", importedAt); put("sizeBytes", sizeBytes)
    }

    companion object {
        fun fromJson(o: JSONObject): MapEntry {
            val cps = ArrayList<ControlPoint>()
            o.optJSONArray("cps")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONArray(i)
                    cps.add(ControlPoint(p.getDouble(0), p.getDouble(1), p.getDouble(2), p.getDouble(3)))
                }
            }
            val neat = o.optJSONArray("neatline")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            return MapEntry(
                o.getString("id"), o.getString("name"), o.getString("fileName"),
                o.getDouble("pageWidth"), o.getDouble("pageHeight"), cps, neat,
                if (o.has("projection")) o.getString("projection") else null,
                o.optLong("importedAt"), o.optLong("sizeBytes")
            )
        }
    }
}

data class Placemark(
    val id: String,
    val mapId: String?,
    val name: String,
    val note: String,
    val lat: Double,
    val lon: Double,
    val time: Long,
    val color: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); mapId?.let { put("mapId", it) }; put("name", name); put("note", note)
        put("lat", lat); put("lon", lon); put("time", time); put("color", color)
    }

    companion object {
        fun fromJson(o: JSONObject) = Placemark(
            o.getString("id"), if (o.has("mapId")) o.getString("mapId") else null,
            o.optString("name"), o.optString("note"), o.getDouble("lat"), o.getDouble("lon"),
            o.optLong("time"), o.optInt("color", 0xFFE53935.toInt())
        )
    }
}

data class TrackPoint(val lat: Double, val lon: Double, val time: Long, val alt: Double?)

data class Track(
    val id: String,
    val mapId: String?,
    val name: String,
    val points: List<TrackPoint>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); mapId?.let { put("mapId", it) }; put("name", name)
        put("pts", JSONArray().apply {
            points.forEach { p ->
                put(JSONArray().put(p.lat).put(p.lon).put(p.time).also { a -> p.alt?.let { a.put(it) } })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Track {
            val pts = ArrayList<TrackPoint>()
            o.optJSONArray("pts")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONArray(i)
                    pts.add(TrackPoint(p.getDouble(0), p.getDouble(1), p.getLong(2), if (p.length() > 3) p.getDouble(3) else null))
                }
            }
            return Track(o.getString("id"), if (o.has("mapId")) o.getString("mapId") else null, o.optString("name"), pts)
        }
    }
}
