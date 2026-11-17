import SwiftUI
import UniformTypeIdentifiers
import CoreLocation

extension Color {
    static let brand = Color(red: 0.12, green: 0.36, blue: 0.23)
    static let brandLight = Color(red: 0.81, green: 0.91, blue: 0.84)
    static let accentOrange = Color(red: 0.91, green: 0.64, blue: 0.24)
}

@main
struct GeoPlanoApp: App {
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            LibraryView()
                .tint(.brand)
                .onAppear { LocationService.shared.start() }
        }
        .onChange(of: phase) { p in
            if p == .active { LocationService.shared.start() }
            if p == .background { LocationService.shared.stopIfIdle(carPlayActive: CarPlaySceneDelegate.isConnected) }
        }
    }
}

/// Biblioteca de planos.
struct LibraryView: View {
    @ObservedObject private var store = Store.shared
    @ObservedObject private var gps = LocationService.shared
    @State private var importing = false
    @State private var busy = false
    @State private var noGeoCount = 0
    @State private var showNoGeo = false
    @State private var renaming: MapEntry?
    @State private var newName = ""
    @State private var path: [String] = []
    @State private var errorText: String?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if store.maps.isEmpty {
                    VStack(spacing: 14) {
                        Image(systemName: "map").font(.system(size: 48)).foregroundStyle(.secondary)
                        Text("Aún no hay planos").font(.headline)
                        Text("Toca + para importar un PDF georreferenciado (por ejemplo, exportado desde ArcGIS Pro con “Exportar información de georreferencia”).")
                            .multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal, 32)
                    }
                } else {
                    List {
                        ForEach(store.sorted(near: gps.location?.coordinate.latitude, gps.location?.coordinate.longitude)) { m in
                            NavigationLink(value: m.id) { MapRow(map: m, location: gps.location) }
                                .contextMenu {
                                    Button { store.selectedMapId = m.id } label: { Label("Usar en CarPlay", systemImage: "car") }
                                    Button { newName = m.name; renaming = m } label: { Label("Renombrar", systemImage: "pencil") }
                                    Button(role: .destructive) { store.deleteMap(m.id) } label: { Label("Eliminar", systemImage: "trash") }
                                }
                                .swipeActions {
                                    Button(role: .destructive) { store.deleteMap(m.id) } label: { Label("Eliminar", systemImage: "trash") }
                                }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("GeoPlano")
            .navigationDestination(for: String.self) { id in
                if let m = store.map(id) { MapScreen(map: m) }
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if busy { ProgressView() } else {
                        Button { importing = true } label: { Image(systemName: "plus.circle.fill").font(.title2) }
                    }
                }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result { importURLs(urls, openAfter: false) }
            }
            .alert("PDF sin georreferencia", isPresented: $showNoGeo) {
                Button("Entendido", role: .cancel) {}
            } message: {
                Text("\(noGeoCount) archivo(s) no traen coordenadas. Se pueden ver, pero sin tu posición.\n\nEn ArcGIS Pro: Compartir → Exportar diseño → PDF → marca “Exportar información de georreferencia”.")
            }
            .alert("Renombrar", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Nombre", text: $newName)
                Button("Guardar") { if let r = renaming, !newName.isEmpty { store.rename(r.id, to: newName) } }
                Button("Cancelar", role: .cancel) {}
            }
            .alert("Error", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(errorText ?? "") }
        }
        .onOpenURL { url in importURLs([url], openAfter: true) }
    }

    private func importURLs(_ urls: [URL], openAfter: Bool) {
        busy = true
        DispatchQueue.global(qos: .userInitiated).async {
            var noGeo = 0
            var last: MapEntry?
            var err: String?
            for u in urls {
                do {
                    let e = try Store.shared.importPDF(from: u)
                    if !e.isGeoreferenced { noGeo += 1 }
                    last = e
                } catch { err = error.localizedDescription }
            }
            DispatchQueue.main.async {
                busy = false
                errorText = err
                if noGeo > 0 { noGeoCount = noGeo; showNoGeo = true }
                if openAfter, let last { path = [last.id] }
            }
        }
    }
}

struct MapRow: View {
    let map: MapEntry
    let location: CLLocationLike?

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let img = UIImage(contentsOfFile: Store.shared.thumbURL(map).path) {
                    Image(uiImage: img).resizable().scaledToFill()
                } else {
                    Image(systemName: "map").foregroundStyle(.secondary)
                }
            }
            .frame(width: 84, height: 64)
            .background(Color(white: 0.9))
            .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                Text(map.name).font(.headline).lineLimit(2)
                Text((map.isGeoreferenced ? (map.projection ?? "Georreferenciado") : "Sin georreferencia") +
                     " · " + String(format: "%.1f MB", Double(map.sizeBytes) / 1_048_576))
                    .font(.caption).foregroundStyle(.secondary)
                status
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder private var status: some View {
        if !map.isGeoreferenced {
            chip("Solo visualización", fg: .gray, bg: Color(white: 0.92))
        } else if let l = location {
            if map.contains(lat: l.coordinate.latitude, lon: l.coordinate.longitude) {
                chip("● Estás aquí", fg: Color(red: 0.05, green: 0.28, blue: 0.63), bg: Color(red: 0.86, green: 0.92, blue: 1))
            } else if let c = map.center() {
                chip("a " + GeoMath.formatDistance(GeoMath.distance(l.coordinate.latitude, l.coordinate.longitude, c.lat, c.lon)),
                     fg: .secondary, bg: Color(red: 0.94, green: 0.93, blue: 0.9))
            }
        } else {
            chip("Esperando GPS", fg: .gray, bg: Color(white: 0.92))
        }
    }

    private func chip(_ t: String, fg: Color, bg: Color) -> some View {
        Text(t).font(.caption.bold()).foregroundStyle(fg)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(bg, in: Capsule())
    }
}

typealias CLLocationLike = CLLocation
