import Foundation
import UIKit
import Combine

struct MapEntry: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var fileName: String
    var pageWidth: Double
    var pageHeight: Double
    var controlPoints: [ControlPoint]
    var neatline: [Double]?
    var projection: String?
    var importedAt: Date
    var sizeBytes: Int64

    var isGeoreferenced: Bool { controlPoints.count >= 3 }

    var transform: GeoTransform? { TransformCache.shared.get(self) }

    var neat: [Double] { neatline ?? [0, 0, pageWidth, pageHeight] }

    func contains(lat: Double, lon: Double) -> Bool {
        guard let t = transform else { return false }
        let p = t.toPage(lat: lat, lon: lon)
        let n = neat
        return Double(p.x) >= n[0] && Double(p.x) <= n[2] && Double(p.y) >= n[1] && Double(p.y) <= n[3]
    }

    func center() -> (lat: Double, lon: Double)? {
        guard let t = transform else { return nil }
        let n = neat
        return t.toGeo(CGPoint(x: (n[0] + n[2]) / 2, y: (n[1] + n[3]) / 2))
    }
}

final class TransformCache {
    static let shared = TransformCache()
    private var cache: [String: GeoTransform] = [:]
    private let lock = NSLock()

    func get(_ m: MapEntry) -> GeoTransform? {
        guard m.isGeoreferenced else { return nil }
        lock.lock(); defer { lock.unlock() }
        if let t = cache[m.id] { return t }
        let t = GeoTransform.from(m.controlPoints)
        cache[m.id] = t
        return t
    }
}

struct Placemark: Codable, Identifiable, Equatable {
    var id: String
    var mapId: String?
    var name: String
    var note: String
    var lat: Double
    var lon: Double
    var time: Date
    var colorHex: String
}

struct TrackPoint: Codable, Equatable {
    var lat: Double
    var lon: Double
    var time: Date
    var alt: Double?
}

struct Track: Codable, Identifiable, Equatable {
    var id: String
    var mapId: String?
    var name: String
    var points: [TrackPoint]

    var length: Double { GeoMath.pathLength(points.map { ($0.lat, $0.lon) }) }
}

/// Biblioteca local: planos PDF, marcadores y tracks. Todo sin conexión.
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var maps: [MapEntry] = []
    @Published private(set) var placemarks: [Placemark] = []
    @Published private(set) var tracks: [Track] = []
    @Published var selectedMapId: String? {
        didSet { UserDefaults.standard.set(selectedMapId, forKey: "selectedMap") }
    }

    private let root: URL
    private let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970; return e }()
    private let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970; return d }()

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent("GeoPlano", isDirectory: true)
        for d in ["maps", "thumbs", "tracks"] {
            try? FileManager.default.createDirectory(at: root.appendingPathComponent(d), withIntermediateDirectories: true)
        }
        maps = load([MapEntry].self, "library.json") ?? []
        placemarks = load([Placemark].self, "placemarks.json") ?? []
        let trackDir = root.appendingPathComponent("tracks")
        let files = (try? FileManager.default.contentsOfDirectory(at: trackDir, includingPropertiesForKeys: nil)) ?? []
        tracks = files.filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(Track.self, from: Data(contentsOf: $0)) }
            .sorted { ($0.points.first?.time ?? .distantPast) < ($1.points.first?.time ?? .distantPast) }
        selectedMapId = UserDefaults.standard.string(forKey: "selectedMap")
    }

    func pdfURL(_ m: MapEntry) -> URL { root.appendingPathComponent("maps").appendingPathComponent(m.fileName) }
    func thumbURL(_ m: MapEntry) -> URL { root.appendingPathComponent("thumbs").appendingPathComponent("\(m.id).png") }
    func map(_ id: String?) -> MapEntry? { maps.first { $0.id == id } }

    /// Copia el PDF, lee la georreferencia y genera la miniatura.
    @discardableResult
    func importPDF(from url: URL) throws -> MapEntry {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let id = UUID().uuidString
        let fileName = "\(id).pdf"
        let dest = root.appendingPathComponent("maps").appendingPathComponent(fileName)
        try FileManager.default.copyItem(at: url, to: dest)
        let geo = GeoPDFParser.parse(url: dest)
        let size = GeoPDFParser.pageSize(url: dest)
        let bytes = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? NSNumber)?.int64Value ?? 0
        var name = url.deletingPathExtension().lastPathComponent
        if name.isEmpty { name = "Plano" }
        let entry = MapEntry(
            id: id, name: name, fileName: fileName,
            pageWidth: Double(size?.width ?? CGFloat(geo?.pageWidth ?? 612)),
            pageHeight: Double(size?.height ?? CGFloat(geo?.pageHeight ?? 792)),
            controlPoints: geo?.controlPoints ?? [], neatline: geo?.neatline,
            projection: geo?.projection, importedAt: Date(), sizeBytes: bytes)
        if let img = PDFRender.image(url: dest, maxPixels: 360) {
            try? img.pngData()?.write(to: thumbURL(entry))
        }
        let update = { self.maps.append(entry); self.save(self.maps, "library.json") }
        if Thread.isMainThread { update() } else { DispatchQueue.main.sync(execute: update) }
        return entry
    }

    func rename(_ id: String, to name: String) {
        guard let i = maps.firstIndex(where: { $0.id == id }) else { return }
        maps[i].name = name
        save(maps, "library.json")
    }

    func deleteMap(_ id: String) {
        guard let m = map(id) else { return }
        try? FileManager.default.removeItem(at: pdfURL(m))
        try? FileManager.default.removeItem(at: thumbURL(m))
        maps.removeAll { $0.id == id }
        save(maps, "library.json")
        if selectedMapId == id { selectedMapId = nil }
    }

    func upsert(_ p: Placemark) {
        if let i = placemarks.firstIndex(where: { $0.id == p.id }) { placemarks[i] = p } else { placemarks.append(p) }
        save(placemarks, "placemarks.json")
    }

    func deletePlacemark(_ id: String) {
        placemarks.removeAll { $0.id == id }
        save(placemarks, "placemarks.json")
    }

    func saveTrack(_ t: Track) {
        if let data = try? encoder.encode(t) {
            try? data.write(to: root.appendingPathComponent("tracks").appendingPathComponent("\(t.id).json"), options: .atomic)
        }
        if let i = tracks.firstIndex(where: { $0.id == t.id }) { tracks[i] = t } else { tracks.append(t) }
    }

    func deleteTrack(_ id: String) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent("tracks").appendingPathComponent("\(id).json"))
        tracks.removeAll { $0.id == id }
    }

    func placemarks(for m: MapEntry) -> [Placemark] {
        placemarks.filter { $0.mapId == m.id || m.contains(lat: $0.lat, lon: $0.lon) }
    }

    func tracks(for m: MapEntry) -> [Track] {
        tracks.filter { $0.mapId == m.id || $0.points.contains { p in m.contains(lat: p.lat, lon: p.lon) } }
    }

    /// Planos donde estás primero, luego por cercanía.
    func sorted(near lat: Double?, _ lon: Double?) -> [MapEntry] {
        guard let lat, let lon else { return maps.sorted { $0.importedAt > $1.importedAt } }
        return maps.sorted { a, b in
            let ia = a.contains(lat: lat, lon: lon), ib = b.contains(lat: lat, lon: lon)
            if ia != ib { return ia }
            let da = a.center().map { GeoMath.distance(lat, lon, $0.lat, $0.lon) } ?? .greatestFiniteMagnitude
            let db = b.center().map { GeoMath.distance(lat, lon, $0.lat, $0.lon) } ?? .greatestFiniteMagnitude
            return da < db
        }
    }

    private func save<T: Encodable>(_ value: T, _ file: String) {
        guard let data = try? encoder.encode(value) else { return }
        try? data.write(to: root.appendingPathComponent(file), options: .atomic)
    }

    private func load<T: Decodable>(_ type: T.Type, _ file: String) -> T? {
        guard let data = try? Data(contentsOf: root.appendingPathComponent(file)) else { return nil }
        return try? decoder.decode(type, from: data)
    }
}

extension UIColor {
    convenience init(hex: String) {
        var v: UInt64 = 0
        Scanner(string: hex.replacingOccurrences(of: "#", with: "")).scanHexInt64(&v)
        self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                  blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}
