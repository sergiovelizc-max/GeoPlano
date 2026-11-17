package cl.geoplano.geo

/**
 * Resultado de leer la georreferenciación de la primera página de un PDF.
 * Los puntos de control están en coordenadas de página (pt) con origen
 * arriba-izquierda, igual que android.graphics.pdf.PdfRenderer.
 */
data class GeoPdfInfo(
    val pageWidth: Double,
    val pageHeight: Double,
    val controlPoints: List<ControlPoint>,
    /** Marco del mapa en página (x1, y1, x2, y2), origen arriba-izquierda. */
    val neatline: DoubleArray?,
    val projectionName: String?
)

/**
 * Lee PDF georreferenciados con la convención ISO 32000 / Adobe (diccionarios
 * /VP → /Measure /Subtype /GEO con /GPTS y /LPTS). Es el formato que generan ArcGIS Pro
 * ("Exportar información de georreferencia"), QGIS y GDAL.
 */
object GeoPdfParser {

    fun parse(bytes: ByteArray): GeoPdfInfo? {
        val r = PdfObjectReader(bytes).readAll()
        val page = findFirstPage(r)
        val pageBox = page?.let { pageBox(r, it) }

        // 1) viewports de la primera página
        val candidates = ArrayList<Pair<PdfDict, PdfDict>>() // (vp, measure)
        if (page != null) collectViewports(r, r.resolve(page["VP"]), candidates)
        // 2) si no, cualquier viewport del documento
        if (candidates.isEmpty()) {
            for (obj in r.objects.values) {
                if (obj is PdfDict && obj["VP"] != null) collectViewports(r, r.resolve(obj["VP"]), candidates)
            }
        }
        if (candidates.isEmpty()) return null

        // el viewport de mayor área suele ser el marco principal (no el mapa de ubicación)
        val best = candidates.maxByOrNull { (vp, _) ->
            val b = numbers(r, vp["BBox"])
            if (b.size == 4) Math.abs((b[2] - b[0]) * (b[3] - b[1])) else 0.0
        } ?: return null
        val (vp, measure) = best

        val box = pageBox ?: doubleArrayOf(0.0, 0.0, 612.0, 792.0)
        val pageW = box[2] - box[0]
        val pageH = box[3] - box[1]
        val bbox = numbers(r, vp["BBox"]).let { if (it.size == 4) it else doubleArrayOf(box[0], box[1], box[2], box[3]) }
        val gpts = numbers(r, measure["GPTS"])
        var lpts = numbers(r, measure["LPTS"])
        if (gpts.size < 6) return null
        if (lpts.size != gpts.size) {
            // por defecto, esquinas del BBox normalizado
            lpts = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0).copyOf(gpts.size)
        }

        val bx1 = minOf(bbox[0], bbox[2]); val bx2 = maxOf(bbox[0], bbox[2])
        val by1 = minOf(bbox[1], bbox[3]); val by2 = maxOf(bbox[1], bbox[3])

        val cps = ArrayList<ControlPoint>()
        var i = 0
        while (i + 1 < gpts.size) {
            val lat = gpts[i]; val lon = gpts[i + 1]
            val u = lpts[i]; val v = lpts[i + 1]
            val xPdf = bx1 + u * (bx2 - bx1)
            val yPdf = by1 + v * (by2 - by1)
            cps.add(ControlPoint(lat, lon, xPdf - box[0], pageH - (yPdf - box[1])))
            i += 2
        }
        if (cps.size < 3) return null

        val neat = doubleArrayOf(bx1 - box[0], pageH - (by2 - box[1]), bx2 - box[0], pageH - (by1 - box[1]))
        val gcs = r.resolve(measure["GCS"]) as? PdfDict
        val wkt = (r.resolve(gcs?.get("WKT")) as? PdfStr)?.v
        val projName = wkt?.let { Regex("^[A-Z]+CS\\[\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?: (r.resolve(gcs?.get("EPSG")) as? PdfNum)?.let { "EPSG:${it.v.toInt()}" }

        return GeoPdfInfo(pageW, pageH, cps, neat, projName)
    }

    private fun collectViewports(r: PdfObjectReader, vpVal: PdfValue?, out: MutableList<Pair<PdfDict, PdfDict>>) {
        val list = when (vpVal) {
            is PdfArray -> vpVal.items
            is PdfDict -> listOf(vpVal)
            else -> return
        }
        for (item in list) {
            val vp = r.resolve(item) as? PdfDict ?: continue
            val m = r.resolve(vp["Measure"]) as? PdfDict ?: continue
            val sub = (m["Subtype"] as? PdfName)?.v
            if (sub != null && sub != "GEO") continue
            if (m["GPTS"] == null) continue
            out.add(vp to m)
        }
    }

    private fun findFirstPage(r: PdfObjectReader): PdfDict? {
        // raíz del árbol de páginas: /Type /Pages sin /Parent
        val root = r.objects.values.firstOrNull {
            it is PdfDict && (it["Type"] as? PdfName)?.v == "Pages" && it["Parent"] == null
        } as? PdfDict
        var node: PdfDict? = root
        var guard = 0
        while (node != null && guard++ < 64) {
            val type = (node["Type"] as? PdfName)?.v
            if (type == "Page") return node
            val kids = r.resolve(node["Kids"]) as? PdfArray ?: break
            node = kids.items.firstNotNullOfOrNull { r.resolve(it) as? PdfDict }
        }
        // alternativa: primer objeto /Type /Page por número de objeto
        return r.objects.entries.sortedBy { it.key }
            .firstOrNull { (it.value as? PdfDict)?.get("Type").let { t -> t is PdfName && t.v == "Page" } }
            ?.value as? PdfDict
    }

    /** CropBox (o MediaBox), heredable desde los nodos /Pages padres. */
    private fun pageBox(r: PdfObjectReader, page: PdfDict): DoubleArray? {
        var node: PdfDict? = page
        var media: DoubleArray? = null
        var crop: DoubleArray? = null
        var guard = 0
        while (node != null && guard++ < 64) {
            if (crop == null) crop = numbers(r, node["CropBox"]).takeIf { it.size == 4 }
            if (media == null) media = numbers(r, node["MediaBox"]).takeIf { it.size == 4 }
            node = r.resolve(node["Parent"]) as? PdfDict
        }
        val b = crop ?: media ?: return null
        return doubleArrayOf(minOf(b[0], b[2]), minOf(b[1], b[3]), maxOf(b[0], b[2]), maxOf(b[1], b[3]))
    }

    private fun numbers(r: PdfObjectReader, v: PdfValue?): DoubleArray {
        val arr = r.resolve(v) as? PdfArray ?: return DoubleArray(0)
        return arr.items.mapNotNull { (r.resolve(it) as? PdfNum)?.v }.toDoubleArray()
    }
}
