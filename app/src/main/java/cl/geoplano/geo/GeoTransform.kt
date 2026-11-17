package cl.geoplano.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Punto de control: coordenada geográfica (grados WGS84) ↔ punto de página (pt, origen arriba-izquierda). */
data class ControlPoint(val lat: Double, val lon: Double, val x: Double, val y: Double)

/**
 * Transformación (lon, lat) → (x, y) de página como homografía 3x3.
 * Con exactamente 4 puntos se resuelve una homografía exacta (cubre bien las
 * proyecciones UTM de un plano a escala predial); con 3 o más de 4 puntos se usa un ajuste afín por mínimos cuadrados.
 */
class GeoTransform private constructor(private val h: DoubleArray, private val inv: DoubleArray) {

    fun toPage(lat: Double, lon: Double): DoubleArray = apply(h, lon, lat)

    /** Devuelve [lat, lon]. */
    fun toGeo(x: Double, y: Double): DoubleArray {
        val r = apply(inv, x, y)
        return doubleArrayOf(r[1], r[0])
    }

    /** Puntos de página por metro, alrededor de una ubicación (para dibujar círculos de precisión). */
    fun pagePerMeter(lat: Double, lon: Double): Double {
        val dLat = 50.0 / 111_320.0
        val a = toPage(lat, lon)
        val b = toPage(lat + dLat, lon)
        val d = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]))
        return d / 50.0
    }

    /** Ángulo (radianes, horario desde "arriba" de la página) en que apunta el norte geográfico. */
    fun northAngle(lat: Double, lon: Double): Double {
        val a = toPage(lat, lon)
        val b = toPage(lat + 0.0005, lon)
        return atan2(b[0] - a[0], -(b[1] - a[1]))
    }

    companion object {
        fun fromControlPoints(pts: List<ControlPoint>): GeoTransform? {
            if (pts.size < 3) return null
            // normalización para estabilidad numérica
            val gx = pts.map { it.lon }; val gy = pts.map { it.lat }
            val px = pts.map { it.x }; val py = pts.map { it.y }
            val tg = normalization(gx, gy)
            val tp = normalization(px, py)
            val src = pts.map { apply(tg, it.lon, it.lat) }
            val dst = pts.map { apply(tp, it.x, it.y) }
            val hn = if (pts.size == 4) homography(src, dst) ?: affine(src, dst) else affine(src, dst)
            hn ?: return null
            val tpInv = invert(tp) ?: return null
            val h = mul(tpInv, mul(hn, tg))
            val hi = invert(h) ?: return null
            return GeoTransform(h, hi)
        }

        private fun normalization(xs: List<Double>, ys: List<Double>): DoubleArray {
            val cx = xs.average(); val cy = ys.average()
            var s = 0.0
            for (i in xs.indices) s += sqrt((xs[i] - cx) * (xs[i] - cx) + (ys[i] - cy) * (ys[i] - cy))
            s /= xs.size
            val k = if (s > 1e-15) sqrt(2.0) / s else 1.0
            return doubleArrayOf(k, 0.0, -k * cx, 0.0, k, -k * cy, 0.0, 0.0, 1.0)
        }

        private fun homography(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i][0] to src[i][1]
                val (u, v) = dst[i][0] to dst[i][1]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val sol = solve(a, 8) ?: return null
            return doubleArrayOf(sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1.0)
        }

        private fun affine(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray? {
            // mínimos cuadrados: [x y 1] * [a b c]^T = u ; idem v
            val ata = Array(3) { DoubleArray(5) }
            for (i in src.indices) {
                val r = doubleArrayOf(src[i][0], src[i][1], 1.0)
                for (j in 0 until 3) {
                    for (k in 0 until 3) ata[j][k] += r[j] * r[k]
                    ata[j][3] += r[j] * dst[i][0]
                    ata[j][4] += r[j] * dst[i][1]
                }
            }
            val mu = Array(3) { j -> doubleArrayOf(ata[j][0], ata[j][1], ata[j][2], ata[j][3]) }
            val mv = Array(3) { j -> doubleArrayOf(ata[j][0], ata[j][1], ata[j][2], ata[j][4]) }
            val su = solve(mu, 3) ?: return null
            val sv = solve(mv, 3) ?: return null
            return doubleArrayOf(su[0], su[1], su[2], sv[0], sv[1], sv[2], 0.0, 0.0, 1.0)
        }

        /** Gauss con pivoteo parcial; matriz aumentada n x (n+1). */
        private fun solve(m: Array<DoubleArray>, n: Int): DoubleArray? {
            for (c in 0 until n) {
                var p = c
                for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
                if (abs(m[p][c]) < 1e-12) return null
                val t = m[c]; m[c] = m[p]; m[p] = t
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = m[r][c] / m[c][c]
                    if (f == 0.0) continue
                    for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }

        fun apply(h: DoubleArray, x: Double, y: Double): DoubleArray {
            val w = h[6] * x + h[7] * y + h[8]
            return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
        }

        private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
                r[i * 3 + j] = s
            }
            return r
        }

        private fun invert(m: DoubleArray): DoubleArray? {
            val a = m[0]; val b = m[1]; val c = m[2]
            val d = m[3]; val e = m[4]; val f = m[5]
            val g = m[6]; val h = m[7]; val i = m[8]
            val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
            val det = a * A + b * B + c * C
            if (abs(det) < 1e-300) return null
            val inv = doubleArrayOf(
                A, -(b * i - c * h), b * f - c * e,
                B, a * i - c * g, -(a * f - c * d),
                C, -(a * h - b * g), a * e - b * d
            )
            for (k in 0 until 9) inv[k] /= det
            return inv
        }
    }
}

object GeoMath {
    private const val R = 6_371_008.8

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Longitud total de una polilínea [lat, lon]. */
    fun pathLength(pts: List<DoubleArray>): Double {
        var s = 0.0
        for (i in 1 until pts.size) s += distance(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
        return s
    }

    /** Área (m²) de un polígono [lat, lon] con proyección equirectangular local; adecuada para predios. */
    fun polygonArea(pts: List<DoubleArray>): Double {
        if (pts.size < 3) return 0.0
        val lat0 = Math.toRadians(pts.map { it[0] }.average())
        val xs = pts.map { Math.toRadians(it[1]) * R * cos(lat0) }
        val ys = pts.map { Math.toRadians(it[0]) * R }
        var s = 0.0
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            s += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(s) / 2
    }

    fun formatDistance(m: Double): String =
        if (m < 1000) String.format(java.util.Locale.US, "%.0f m", m)
        else String.format(java.util.Locale.US, "%.2f km", m / 1000)

    fun formatArea(m2: Double): String =
        if (m2 < 10_000) String.format(java.util.Locale.US, "%.0f m²", m2)
        else String.format(java.util.Locale.US, "%.2f ha", m2 / 10_000)

    fun formatLatLon(lat: Double, lon: Double): String =
        String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon)

    /** Rumbo inicial (grados desde el norte, 0–360) de 1 a 2. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun cardinal(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return names[(((deg + 22.5) % 360) / 45).toInt()]
    }

    /** UTM WGS84: [zona, este, norte, 1 si hemisferio sur]. */
    fun toUtm(lat: Double, lon: Double): DoubleArray {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)
        val zone = Math.floor((lon + 180) / 6).toInt() + 1
        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val phi = Math.toRadians(lat)
        val n = a / sqrt(1 - e2 * sin(phi) * sin(phi))
        val t = Math.tan(phi) * Math.tan(phi)
        val c = ep2 * cos(phi) * cos(phi)
        val aa = cos(phi) * (Math.toRadians(lon) - lon0)
        val e4 = e2 * e2; val e6 = e4 * e2
        val m = a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
            (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
            (35 * e6 / 3072) * sin(6 * phi))
        val east = k0 * n * (aa + (1 - t + c) * Math.pow(aa, 3.0) / 6 +
            (5 - 18 * t + t * t + 72 * c - 58 * ep2) * Math.pow(aa, 5.0) / 120) + 500000.0
        var north = k0 * (m + n * Math.tan(phi) * (aa * aa / 2 +
            (5 - t + 9 * c + 4 * c * c) * Math.pow(aa, 4.0) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * Math.pow(aa, 6.0) / 720))
        val south = lat < 0
        if (south) north += 10_000_000.0
        return doubleArrayOf(zone.toDouble(), east, north, if (south) 1.0 else 0.0)
    }

    fun formatUtm(lat: Double, lon: Double): String {
        val u = toUtm(lat, lon)
        return String.format(java.util.Locale.US, "%d%s  E %.0f  N %.0f", u[0].toInt(), if (u[3] == 1.0) "S" else "N", u[1], u[2])
    }
}
