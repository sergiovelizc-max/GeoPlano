import Foundation
import CoreGraphics

/// Punto de control: geográfico (WGS84) ↔ página (pt, origen arriba-izquierda).
struct ControlPoint: Codable, Equatable {
    var lat: Double
    var lon: Double
    var x: Double
    var y: Double
}

struct GeoPDFInfo {
    var pageWidth: Double
    var pageHeight: Double
    var controlPoints: [ControlPoint]
    /// Marco del mapa en página: x1, y1, x2, y2.
    var neatline: [Double]?
    var projection: String?
}

/// Lee la georreferenciación ISO 32000 (/VP → /Measure /GEO con /GPTS y /LPTS)
/// que exportan ArcGIS Pro, QGIS y GDAL. CoreGraphics resuelve referencias y object streams.
enum GeoPDFParser {

    static func pageSize(url: URL) -> CGSize? {
        guard let doc = CGPDFDocument(url as CFURL), let page = doc.page(at: 1) else { return nil }
        return page.getBoxRect(.cropBox).size
    }

    static func parse(url: URL) -> GeoPDFInfo? {
        guard let doc = CGPDFDocument(url as CFURL), let page = doc.page(at: 1),
              let dict = page.dictionary else { return nil }
        let box = page.getBoxRect(.cropBox)
        var vpArrayRef: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(dict, "VP", &vpArrayRef), let vps = vpArrayRef else { return nil }

        var best: (bbox: [Double], measure: CGPDFDictionaryRef, area: Double)?
        for i in 0..<CGPDFArrayGetCount(vps) {
            var vpRef: CGPDFDictionaryRef?
            guard CGPDFArrayGetDictionary(vps, i, &vpRef), let vp = vpRef else { continue }
            var mRef: CGPDFDictionaryRef?
            guard CGPDFDictionaryGetDictionary(vp, "Measure", &mRef), let m = mRef else { continue }
            if let sub = name(m, "Subtype"), sub != "GEO" { continue }
            let bbox = numbers(vp, "BBox")
            guard bbox.count == 4, numbers(m, "GPTS").count >= 6 else { continue }
            let area = abs((bbox[2] - bbox[0]) * (bbox[3] - bbox[1]))
            if best == nil || area > best!.area { best = (bbox, m, area) }
        }
        guard let chosen = best else { return nil }

        let gpts = numbers(chosen.measure, "GPTS")
        var lpts = numbers(chosen.measure, "LPTS")
        if lpts.count != gpts.count {
            lpts = Array([0, 0, 0, 1, 1, 1, 1, 0].prefix(gpts.count).map { Double($0) })
            while lpts.count < gpts.count { lpts.append(0) }
        }
        let b = chosen.bbox
        let bx1 = min(b[0], b[2]), bx2 = max(b[0], b[2])
        let by1 = min(b[1], b[3]), by2 = max(b[1], b[3])
        let ox = Double(box.minX), oy = Double(box.minY), ph = Double(box.height)

        var cps: [ControlPoint] = []
        var i = 0
        while i + 1 < gpts.count {
            let xPdf = bx1 + lpts[i] * (bx2 - bx1)
            let yPdf = by1 + lpts[i + 1] * (by2 - by1)
            cps.append(ControlPoint(lat: gpts[i], lon: gpts[i + 1], x: xPdf - ox, y: ph - (yPdf - oy)))
            i += 2
        }
        guard cps.count >= 3 else { return nil }
        let neat = [bx1 - ox, ph - (by2 - oy), bx2 - ox, ph - (by1 - oy)]

        var projection: String?
        var gcsRef: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(chosen.measure, "GCS", &gcsRef), let gcs = gcsRef {
            var s: CGPDFStringRef?
            if CGPDFDictionaryGetString(gcs, "WKT", &s), let s, let wkt = CGPDFStringCopyTextString(s) as String? {
                if let open = wkt.range(of: "[\""), let close = wkt.range(of: "\"", range: open.upperBound..<wkt.endIndex) {
                    projection = String(wkt[open.upperBound..<close.lowerBound])
                }
            }
            var epsg: CGPDFInteger = 0
            if projection == nil, CGPDFDictionaryGetInteger(gcs, "EPSG", &epsg) { projection = "EPSG:\(epsg)" }
        }
        return GeoPDFInfo(pageWidth: Double(box.width), pageHeight: Double(box.height),
                          controlPoints: cps, neatline: neat, projection: projection)
    }

    private static func name(_ d: CGPDFDictionaryRef, _ key: String) -> String? {
        var p: UnsafePointer<CChar>?
        guard CGPDFDictionaryGetName(d, key, &p), let p else { return nil }
        return String(cString: p)
    }

    private static func numbers(_ d: CGPDFDictionaryRef, _ key: String) -> [Double] {
        var arrRef: CGPDFArrayRef?
        guard CGPDFDictionaryGetArray(d, key, &arrRef), let arr = arrRef else { return [] }
        var out: [Double] = []
        for i in 0..<CGPDFArrayGetCount(arr) {
            var v: CGPDFReal = 0
            if CGPDFArrayGetNumber(arr, i, &v) { out.append(Double(v)) }
        }
        return out
    }
}

/// (lon, lat) → página como homografía 3x3 (4 puntos) o afín por mínimos cuadrados.
final class GeoTransform {
    private let h: [Double]
    private let inv: [Double]

    private init(h: [Double], inv: [Double]) { self.h = h; self.inv = inv }

    func toPage(lat: Double, lon: Double) -> CGPoint {
        let r = GeoTransform.apply(h, lon, lat)
        return CGPoint(x: r.0, y: r.1)
    }

    /// (lat, lon)
    func toGeo(_ p: CGPoint) -> (lat: Double, lon: Double) {
        let r = GeoTransform.apply(inv, Double(p.x), Double(p.y))
        return (r.1, r.0)
    }

    func pagePerMeter(lat: Double, lon: Double) -> Double {
        let a = toPage(lat: lat, lon: lon)
        let b = toPage(lat: lat + 50.0 / 111_320.0, lon: lon)
        return hypot(Double(a.x - b.x), Double(a.y - b.y)) / 50.0
    }

    /// Radianes, horario desde "arriba" de la página, hacia donde apunta el norte.
    func northAngle(lat: Double, lon: Double) -> Double {
        let a = toPage(lat: lat, lon: lon)
        let b = toPage(lat: lat + 0.0005, lon: lon)
        return atan2(Double(b.x - a.x), -Double(b.y - a.y))
    }

    static func from(_ pts: [ControlPoint]) -> GeoTransform? {
        guard pts.count >= 3 else { return nil }
        let tg = normalization(pts.map { $0.lon }, pts.map { $0.lat })
        let tp = normalization(pts.map { $0.x }, pts.map { $0.y })
        let src = pts.map { apply(tg, $0.lon, $0.lat) }
        let dst = pts.map { apply(tp, $0.x, $0.y) }
        guard let hn = (pts.count == 4 ? homography(src, dst) : nil) ?? affine(src, dst),
              let tpInv = invert(tp) else { return nil }
        let full = mul(tpInv, mul(hn, tg))
        guard let fi = invert(full) else { return nil }
        return GeoTransform(h: full, inv: fi)
    }

    static func apply(_ h: [Double], _ x: Double, _ y: Double) -> (Double, Double) {
        let w = h[6] * x + h[7] * y + h[8]
        return ((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    private static func normalization(_ xs: [Double], _ ys: [Double]) -> [Double] {
        let cx = xs.reduce(0, +) / Double(xs.count), cy = ys.reduce(0, +) / Double(ys.count)
        var s = 0.0
        for i in xs.indices { s += hypot(xs[i] - cx, ys[i] - cy) }
        s /= Double(xs.count)
        let k = s > 1e-15 ? sqrt(2.0) / s : 1.0
        return [k, 0, -k * cx, 0, k, -k * cy, 0, 0, 1]
    }

    private static func homography(_ src: [(Double, Double)], _ dst: [(Double, Double)]) -> [Double]? {
        var a = [[Double]](repeating: [Double](repeating: 0, count: 9), count: 8)
        for i in 0..<4 {
            let (x, y) = src[i], (u, v) = dst[i]
            a[2 * i] = [x, y, 1, 0, 0, 0, -u * x, -u * y, u]
            a[2 * i + 1] = [0, 0, 0, x, y, 1, -v * x, -v * y, v]
        }
        guard let s = solve(&a, 8) else { return nil }
        return [s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], 1]
    }

    private static func affine(_ src: [(Double, Double)], _ dst: [(Double, Double)]) -> [Double]? {
        var mu = [[Double]](repeating: [0, 0, 0, 0], count: 3)
        var mv = [[Double]](repeating: [0, 0, 0, 0], count: 3)
        for i in src.indices {
            let r = [src[i].0, src[i].1, 1.0]
            for j in 0..<3 {
                for k in 0..<3 { mu[j][k] += r[j] * r[k]; mv[j][k] += r[j] * r[k] }
                mu[j][3] += r[j] * dst[i].0
                mv[j][3] += r[j] * dst[i].1
            }
        }
        guard let su = solve(&mu, 3), let sv = solve(&mv, 3) else { return nil }
        return [su[0], su[1], su[2], sv[0], sv[1], sv[2], 0, 0, 1]
    }

    private static func solve(_ m: inout [[Double]], _ n: Int) -> [Double]? {
        for c in 0..<n {
            var p = c
            for r in (c + 1)..<max(c + 1, n) where abs(m[r][c]) > abs(m[p][c]) { p = r }
            if abs(m[p][c]) < 1e-12 { return nil }
            m.swapAt(c, p)
            for r in 0..<n where r != c {
                let f = m[r][c] / m[c][c]
                if f == 0 { continue }
                for k in c...n { m[r][k] -= f * m[c][k] }
            }
        }
        return (0..<n).map { m[$0][n] / m[$0][$0] }
    }

    private static func mul(_ a: [Double], _ b: [Double]) -> [Double] {
        var r = [Double](repeating: 0, count: 9)
        for i in 0..<3 { for j in 0..<3 { for k in 0..<3 { r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j] } } }
        return r
    }

    private static func invert(_ m: [Double]) -> [Double]? {
        let (a, b, c, d, e, f, g, h, i) = (m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8])
        let A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g
        let det = a * A + b * B + c * C
        if abs(det) < 1e-300 { return nil }
        return [A, -(b * i - c * h), b * f - c * e,
                B, a * i - c * g, -(a * f - c * d),
                C, -(a * h - b * g), a * e - b * d].map { $0 / det }
    }
}

enum GeoMath {
    static let earthRadius = 6_371_008.8

    static func distance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let dp = p2 - p1, dl = (lon2 - lon1) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadius * atan2(sqrt(a), sqrt(1 - a))
    }

    static func pathLength(_ pts: [(Double, Double)]) -> Double {
        guard pts.count > 1 else { return 0 }
        return (1..<pts.count).reduce(0) { $0 + distance(pts[$1 - 1].0, pts[$1 - 1].1, pts[$1].0, pts[$1].1) }
    }

    static func polygonArea(_ pts: [(Double, Double)]) -> Double {
        guard pts.count >= 3 else { return 0 }
        let lat0 = pts.map { $0.0 }.reduce(0, +) / Double(pts.count) * .pi / 180
        let xs = pts.map { $0.1 * .pi / 180 * earthRadius * cos(lat0) }
        let ys = pts.map { $0.0 * .pi / 180 * earthRadius }
        var s = 0.0
        for i in pts.indices { let j = (i + 1) % pts.count; s += xs[i] * ys[j] - xs[j] * ys[i] }
        return abs(s) / 2
    }

    static func bearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180, dl = (lon2 - lon1) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    static func cardinal(_ deg: Double) -> String {
        let names = ["N", "NE", "E", "SE", "S", "SO", "O", "NO"]
        return names[Int(((deg + 22.5).truncatingRemainder(dividingBy: 360)) / 45) % 8]
    }

    static func formatDistance(_ m: Double) -> String {
        m < 1000 ? String(format: "%.0f m", m) : String(format: "%.2f km", m / 1000)
    }

    static func formatArea(_ m2: Double) -> String {
        m2 < 10_000 ? String(format: "%.0f m²", m2) : String(format: "%.2f ha", m2 / 10_000)
    }

    static func formatLatLon(_ lat: Double, _ lon: Double) -> String {
        String(format: "%.6f, %.6f", lat, lon)
    }

    /// UTM WGS84 → (zona, este, norte, sur)
    static func toUTM(_ lat: Double, _ lon: Double) -> (zone: Int, east: Double, north: Double, south: Bool) {
        let a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996
        let e2 = f * (2 - f), ep2 = e2 / (1 - e2)
        let zone = Int(floor((lon + 180) / 6)) + 1
        let lon0 = (Double((zone - 1) * 6 - 180) + 3) * .pi / 180
        let phi = lat * .pi / 180
        let n = a / sqrt(1 - e2 * sin(phi) * sin(phi))
        let t = tan(phi) * tan(phi)
        let c = ep2 * cos(phi) * cos(phi)
        let aa = cos(phi) * (lon * .pi / 180 - lon0)
        let e4 = e2 * e2, e6 = e4 * e2
        let m1 = (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
        let m2 = (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi)
        let m3 = (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi)
        let m4 = (35 * e6 / 3072) * sin(6 * phi)
        let m = a * (m1 - m2 + m3 - m4)
        let east = k0 * n * (aa + (1 - t + c) * pow(aa, 3) / 6 +
                             (5 - 18 * t + t * t + 72 * c - 58 * ep2) * pow(aa, 5) / 120) + 500000
        var north = k0 * (m + n * tan(phi) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * pow(aa, 4) / 24 +
                                               (61 - 58 * t + t * t + 600 * c - 330 * ep2) * pow(aa, 6) / 720))
        if lat < 0 { north += 10_000_000 }
        return (zone, east, north, lat < 0)
    }

    static func formatUTM(_ lat: Double, _ lon: Double) -> String {
        let u = toUTM(lat, lon)
        return String(format: "%d%@  E %.0f  N %.0f", u.zone, u.south ? "S" : "N", u.east, u.north)
    }
}
