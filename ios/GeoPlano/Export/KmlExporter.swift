import Foundation

/// Exporta marcadores y tracks a KML (Google Earth, ArcGIS Pro, QGIS) y CSV.
enum KmlExporter {

    static func write(title: String, placemarks: [Placemark], tracks: [Track]) -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let base = title.replacingOccurrences(of: "[^A-Za-z0-9_\\- ]", with: "_", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        let safe = base.isEmpty ? "GeoPlano" : base
        var urls: [URL] = []
        let kml = dir.appendingPathComponent("\(safe).kml")
        try? kmlString(title: title, placemarks: placemarks, tracks: tracks).write(to: kml, atomically: true, encoding: .utf8)
        urls.append(kml)
        if !placemarks.isEmpty {
            let csv = dir.appendingPathComponent("\(safe)_marcadores.csv")
            try? ("\u{FEFF}" + csvString(placemarks)).write(to: csv, atomically: true, encoding: .utf8)
            urls.append(csv)
        }
        return urls
    }

    static func kmlString(title: String, placemarks: [Placemark], tracks: [Track]) -> String {
        let iso = ISO8601DateFormatter()
        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n"
        s += "<name>\(esc(title))</name>\n"
        s += "<Style id=\"trk\"><LineStyle><color>ff3d8ee8</color><width>4</width></LineStyle></Style>\n"
        if !placemarks.isEmpty {
            s += "<Folder><name>Marcadores</name>\n"
            for p in placemarks {
                s += "<Placemark><name>\(esc(p.name))</name>"
                if !p.note.isEmpty { s += "<description>\(esc(p.note))</description>" }
                s += "<TimeStamp><when>\(iso.string(from: p.time))</when></TimeStamp>"
                s += "<Point><coordinates>\(p.lon),\(p.lat)</coordinates></Point></Placemark>\n"
            }
            s += "</Folder>\n"
        }
        if !tracks.isEmpty {
            s += "<Folder><name>Tracks</name>\n"
            for t in tracks {
                s += "<Placemark><name>\(esc(t.name))</name><styleUrl>#trk</styleUrl><LineString><tessellate>1</tessellate><coordinates>"
                for p in t.points {
                    s += "\(p.lon),\(p.lat)"
                    if let a = p.alt { s += String(format: ",%.1f", a) }
                    s += " "
                }
                s += "</coordinates></LineString></Placemark>\n"
            }
            s += "</Folder>\n"
        }
        return s + "</Document></kml>\n"
    }

    static func csvString(_ placemarks: [Placemark]) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        var s = "nombre;nota;latitud;longitud;fecha\n"
        for p in placemarks {
            s += "\(q(p.name));\(q(p.note));\(p.lat);\(p.lon);\(f.string(from: p.time))\n"
        }
        return s
    }

    private static func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }

    private static func q(_ s: String) -> String { "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
}
