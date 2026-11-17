package cl.geoplano.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GeoPdfParserTest {

    private val pdf = """%PDF-1.7
1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 1190 842] >> endobj
3 0 obj << /Type /Page /Parent 2 0 R /VP [ << /Type /Viewport /BBox [900 600 1080 750] /Measure << /Subtype /GEO /GPTS [-40 -75 -30 -75 -30 -70 -40 -70] /LPTS [0 0 0 1 1 1 1 0] >> >> << /Type /Viewport /BBox [100 80 1090 760] /Measure 4 0 R >> ] >> endobj
4 0 obj << /Type /Measure /Subtype /GEO /GPTS [-37.3 -72.6 -37.2 -72.6 -37.2 -72.45 -37.3 -72.45] /LPTS [0 0 0 1 1 1 1 0] /GCS << /Type /PROJCS /WKT (PROJCS["WGS_1984_UTM_Zone_18S",GEOGCS[]]) >> >> endobj
trailer << /Root 1 0 R >>
%%EOF""".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun leeViewportPrincipalYTransforma() {
        val info = GeoPdfParser.parse(pdf)
        assertNotNull(info)
        info!!
        assertEquals(4, info.controlPoints.size)
        assertEquals("WGS_1984_UTM_Zone_18S", info.projectionName)
        val t = GeoTransform.fromControlPoints(info.controlPoints)!!
        val p = t.toPage(-37.25, -72.525)
        assertEquals(595.0, p[0], 1e-6)
        assertEquals(422.0, p[1], 1e-6)
        val g = t.toGeo(p[0], p[1])
        assertEquals(-37.25, g[0], 1e-9)
        assertEquals(-72.525, g[1], 1e-9)
    }
}
