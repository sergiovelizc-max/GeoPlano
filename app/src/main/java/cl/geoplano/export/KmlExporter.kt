package cl.geoplano.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import cl.geoplano.data.MapEntry
import cl.geoplano.data.Placemark
import cl.geoplano.data.Track
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Exporta marcadores y tracks a KML (abre en Google Earth, ArcGIS Pro, QGIS) y CSV. */
object KmlExporter {

    fun buildKml(title: String, placemarks: List<Placemark>, tracks: List<Track>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        sb.append("<name>").append(esc(title)).append("</name>\n")
        sb.append("<Style id=\"trk\"><LineStyle><color>ff3d8ee8</color><width>4</width></LineStyle></Style>\n")
        if (placemarks.isNotEmpty()) {
            sb.append("<Folder><name>Marcadores</name>\n")
            for (p in placemarks) {
                sb.append("<Placemark><name>").append(esc(p.name)).append("</name>")
                if (p.note.isNotBlank()) sb.append("<description>").append(esc(p.note)).append("</description>")
                sb.append("<TimeStamp><when>").append(iso.format(Date(p.time))).append("</when></TimeStamp>")
                sb.append("<Point><coordinates>").append(p.lon).append(',').append(p.lat).append("</coordinates></Point></Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        if (tracks.isNotEmpty()) {
            sb.append("<Folder><name>Tracks</name>\n")
            for (t in tracks) {
                sb.append("<Placemark><name>").append(esc(t.name)).append("</name><styleUrl>#trk</styleUrl><LineString><tessellate>1</tessellate><coordinates>")
                for (pt in t.points) {
                    sb.append(pt.lon).append(',').append(pt.lat)
                    pt.alt?.let { sb.append(',').append(String.format(Locale.US, "%.1f", it)) }
                    sb.append(' ')
                }
                sb.append("</coordinates></LineString></Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document></kml>\n")
        return sb.toString()
    }

    fun buildCsv(placemarks: List<Placemark>): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("nombre;nota;latitud;longitud;fecha\n")
        for (p in placemarks) {
            sb.append(csv(p.name)).append(';').append(csv(p.note)).append(';')
                .append(p.lat).append(';').append(p.lon).append(';').append(df.format(Date(p.time))).append('\n')
        }
        return sb.toString()
    }

    /** Escribe los archivos y abre el menú "Compartir" (correo, WhatsApp, Drive, OneDrive…). */
    fun share(context: Context, map: MapEntry?, placemarks: List<Placemark>, tracks: List<Track>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val base = (map?.name ?: "GeoPlano").replace(Regex("[^A-Za-z0-9_\\- ]"), "_").trim().ifEmpty { "GeoPlano" }
        val kml = File(dir, "$base.kml").apply { writeText(buildKml(map?.name ?: "GeoPlano", placemarks, tracks)) }
        val files = arrayListOf(kml)
        if (placemarks.isNotEmpty()) files.add(File(dir, "${base}_marcadores.csv").apply { writeText("﻿" + buildCsv(placemarks)) })
        val auth = context.packageName + ".files"
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, auth, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "GeoPlano – ${map?.name ?: "datos"}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Exportar KML / CSV"))
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
