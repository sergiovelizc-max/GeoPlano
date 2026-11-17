import SwiftUI
import Combine
import CoreLocation

/// Conecta la vista UIKit del plano con SwiftUI.
struct CanvasRepresentable: UIViewRepresentable {
    let canvas: MapCanvasView
    func makeUIView(context: Context) -> MapCanvasView { canvas }
    func updateUIView(_ uiView: MapCanvasView, context: Context) {}
}

final class MapController: ObservableObject {
    let canvas = MapCanvasView(frame: .zero)
    @Published var follow = false
    @Published var measuring = false
    @Published var measurePts: [(Double, Double)] = []
    @Published var center: (Double, Double)?
    var bag = Set<AnyCancellable>()
}

struct PlacemarkDraft: Identifiable {
    var id: String
    var existing: Bool
    var name: String
    var note: String
    var lat: Double
    var lon: Double
    var colorHex: String
    var time: Date
}

struct MapScreen: View {
    let map: MapEntry
    @StateObject private var ctl = MapController()
    @ObservedObject private var gps = LocationService.shared
    @ObservedObject private var recorder = TrackRecorder.shared
    @ObservedObject private var store = Store.shared
    @State private var draft: PlacemarkDraft?
    @State private var showPlacemarks = false
    @State private var showTracks = false
    @State private var showInfo = false
    @State private var shareItems: [URL]?
    @State private var toast: String?

    var body: some View {
        ZStack {
            CanvasRepresentable(canvas: ctl.canvas).ignoresSafeArea(edges: .bottom)

            VStack(spacing: 0) {
                infoBar
                Spacer()
                if ctl.measuring { measurePanel } else if crosshairVisible { crossPanel }
            }

            if map.isGeoreferenced {
                VStack(spacing: 12) {
                    Spacer()
                    roundButton(recorder.isRecording ? "stop.fill" : "record.circle", fg: .red, bg: .white) { toggleTrack() }
                    roundButton("mappin.and.ellipse", fg: .accentOrange, bg: .white) {
                        if let l = gps.location { newPlacemark(l.coordinate.latitude, l.coordinate.longitude) }
                        else { flash("Sin GPS aún. Mantén presionado el plano para marcar.") }
                    }
                    roundButton("location.fill", fg: .white, bg: ctl.follow ? Color(red: 0.12, green: 0.53, blue: 0.9) : .brand, big: true) { locate() }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.trailing, 16)
                .padding(.bottom, 28)
            }

            if let toast {
                Text(toast).font(.subheadline).padding(10)
                    .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 10))
                    .foregroundStyle(.white)
                    .frame(maxHeight: .infinity, alignment: .center)
                    .transition(.opacity)
            }
        }
        .navigationTitle(map.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    if map.isGeoreferenced {
                        Button { toggleMeasure() } label: { Label(ctl.measuring ? "Terminar medición" : "Medir distancia / área", systemImage: "ruler") }
                        Button { showPlacemarks = true } label: { Label("Marcadores", systemImage: "mappin") }
                        Button { showTracks = true } label: { Label("Tracks", systemImage: "point.topleft.down.curvedto.point.bottomright.up") }
                        Button { export() } label: { Label("Exportar KML / CSV", systemImage: "square.and.arrow.up") }
                        Button { store.selectedMapId = map.id; flash("Se mostrará en CarPlay") } label: { Label("Usar en CarPlay", systemImage: "car") }
                    }
                    Button { showInfo = true } label: { Label("Información del plano", systemImage: "info.circle") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .onAppear(perform: setup)
        .onDisappear { ctl.bag.removeAll() }
        .sheet(item: $draft) { d in PlacemarkEditor(draft: d, mapId: map.id) }
        .sheet(isPresented: $showPlacemarks) { placemarkList }
        .sheet(isPresented: $showTracks) { trackList }
        .sheet(isPresented: Binding(get: { shareItems != nil }, set: { if !$0 { shareItems = nil } })) {
            ShareSheet(items: shareItems ?? [])
        }
        .alert(map.name, isPresented: $showInfo) { Button("OK", role: .cancel) {} } message: { Text(infoText) }
    }

    private var crosshairVisible: Bool { map.isGeoreferenced && !ctl.follow && !ctl.measuring }

    // MARK: paneles

    private var infoBar: some View {
        Text(infoLine)
            .font(.system(size: 13, design: .monospaced))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(.black.opacity(0.75))
    }

    private var infoLine: String {
        guard map.isGeoreferenced else { return "Este PDF no trae georreferencia. Se puede ver, pero sin GPS." }
        guard let l = gps.location else { return gps.hasPermission ? "Buscando GPS…" : "Sin permiso de ubicación" }
        var s = GeoMath.formatLatLon(l.coordinate.latitude, l.coordinate.longitude)
        s += "  ±\(Int(l.horizontalAccuracy)) m"
        if l.verticalAccuracy >= 0 { s += "  \(Int(l.altitude)) msnm" }
        if !map.contains(lat: l.coordinate.latitude, lon: l.coordinate.longitude), let c = map.center() {
            s += "\nFuera del plano · centro a " + GeoMath.formatDistance(GeoMath.distance(l.coordinate.latitude, l.coordinate.longitude, c.lat, c.lon))
        }
        if let t = recorder.active { s += "\n● Grabando: " + GeoMath.formatDistance(t.length) }
        return s
    }

    private var crossPanel: some View {
        HStack(alignment: .center) {
            Text(crossText).font(.system(size: 12.5, design: .monospaced)).foregroundStyle(.black)
            Spacer(minLength: 4)
            Button {
                if let c = ctl.center { newPlacemark(c.0, c.1) }
            } label: { Text("Marcar\naquí").font(.subheadline.bold()).multilineTextAlignment(.center) }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 12))
        .padding(.leading, 12).padding(.trailing, 84).padding(.bottom, 28)
    }

    private var crossText: String {
        guard let c = ctl.center else { return "Desplaza el plano" }
        var s = "UTM " + GeoMath.formatUTM(c.0, c.1) + "\n" + GeoMath.formatLatLon(c.0, c.1)
        if let l = gps.location {
            let d = GeoMath.distance(l.coordinate.latitude, l.coordinate.longitude, c.0, c.1)
            let b = GeoMath.bearing(l.coordinate.latitude, l.coordinate.longitude, c.0, c.1)
            s += "\nDesde ti: " + GeoMath.formatDistance(d) + " · \(Int(b))° " + GeoMath.cardinal(b)
        }
        if !map.contains(lat: c.0, lon: c.1) { s += "\n(fuera del marco del plano)" }
        return s
    }

    private var measurePanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(measureText).font(.subheadline).foregroundStyle(.black)
            HStack {
                Spacer()
                Button("Deshacer") { if !ctl.measurePts.isEmpty { ctl.measurePts.removeLast(); ctl.canvas.measure = ctl.measurePts } }
                Button("Cerrar") { toggleMeasure() }
            }
        }
        .padding(12)
        .background(.white.opacity(0.95), in: RoundedRectangle(cornerRadius: 12))
        .padding(.leading, 12).padding(.trailing, 84).padding(.bottom, 28)
    }

    private var measureText: String {
        let p = ctl.measurePts
        if p.count < 2 { return "Toca el plano para agregar puntos" }
        if p.count == 2 { return "Distancia: " + GeoMath.formatDistance(GeoMath.pathLength(p)) }
        return "Recorrido: " + GeoMath.formatDistance(GeoMath.pathLength(p)) +
            "\nPerímetro: " + GeoMath.formatDistance(GeoMath.pathLength(p + [p[0]])) +
            "   Área: " + GeoMath.formatArea(GeoMath.polygonArea(p))
    }

    private var infoText: String {
        var s = String(format: "Archivo: %.1f MB\n", Double(map.sizeBytes) / 1_048_576)
        s += "Página: \(Int(map.pageWidth)) × \(Int(map.pageHeight)) pt\n"
        if map.isGeoreferenced {
            s += "Sistema: \(map.projection ?? "—")\nPuntos de control: \(map.controlPoints.count)\n"
            if let c = map.center() { s += "Centro: " + GeoMath.formatLatLon(c.lat, c.lon) }
        } else {
            s += "Sin georreferencia. Exporta el plano desde ArcGIS Pro marcando “Exportar información de georreferencia”."
        }
        return s
    }

    private func roundButton(_ icon: String, fg: Color, bg: Color, big: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: big ? 22 : 18, weight: .semibold))
                .foregroundStyle(fg)
                .frame(width: big ? 56 : 44, height: big ? 56 : 44)
                .background(bg, in: Circle())
                .shadow(color: .black.opacity(0.25), radius: 3, y: 1)
        }
    }

    // MARK: lógica

    private func setup() {
        let ctl = self.ctl
        let cv = ctl.canvas
        if cv.map?.id != map.id { cv.setMap(map, url: store.pdfURL(map)) }
        store.selectedMapId = map.id
        cv.crosshair = crosshairVisible
        cv.onUserMoved = { [weak ctl] in
            guard let ctl, ctl.follow else { return }
            ctl.follow = false
            ctl.canvas.crosshair = !ctl.measuring && map.isGeoreferenced
        }
        cv.onCenterChanged = { [weak ctl] lat, lon in ctl?.center = (lat, lon) }
        cv.onTapGeo = { [weak ctl] lat, lon in
            guard let ctl, ctl.measuring else { return false }
            ctl.measurePts.append((lat, lon))
            ctl.canvas.measure = ctl.measurePts
            return true
        }
        cv.onLongPressGeo = { [weak ctl] lat, lon in
            guard let ctl, !ctl.measuring else { return }
            newPlacemark(lat, lon)
        }
        cv.onPlacemarkTap = { p in
            draft = PlacemarkDraft(id: p.id, existing: true, name: p.name, note: p.note, lat: p.lat, lon: p.lon, colorHex: p.colorHex, time: p.time)
        }

        ctl.bag.removeAll()
        gps.$location.receive(on: DispatchQueue.main).sink { [weak ctl] loc in
            guard let ctl else { return }
            ctl.canvas.location = loc
            if ctl.follow, let loc { ctl.canvas.centerOn(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude) }
        }.store(in: &ctl.bag)
        store.$placemarks.receive(on: DispatchQueue.main).sink { [weak ctl] _ in
            ctl?.canvas.placemarks = Store.shared.placemarks(for: map)
        }.store(in: &ctl.bag)
        store.$tracks.receive(on: DispatchQueue.main).sink { [weak ctl] _ in
            ctl?.canvas.tracks = Store.shared.tracks(for: map)
        }.store(in: &ctl.bag)
        recorder.$active.receive(on: DispatchQueue.main).sink { [weak ctl] t in
            ctl?.canvas.activeTrack = t
        }.store(in: &ctl.bag)
    }

    private func locate() {
        guard let l = gps.location else { flash("Esperando señal GPS…"); return }
        if !map.contains(lat: l.coordinate.latitude, lon: l.coordinate.longitude) { flash("Estás fuera de este plano") }
        ctl.follow = true
        ctl.canvas.crosshair = false
        ctl.canvas.centerOn(lat: l.coordinate.latitude, lon: l.coordinate.longitude, zoomIn: true, animated: true)
    }

    private func toggleMeasure() {
        ctl.measuring.toggle()
        ctl.measurePts = []
        ctl.canvas.measure = []
        ctl.canvas.crosshair = crosshairVisible
    }

    private func toggleTrack() {
        if recorder.isRecording {
            recorder.stop()
            flash("Track guardado")
        } else {
            guard gps.hasPermission else { flash("Necesitas conceder el permiso de ubicación"); return }
            recorder.start(mapId: map.id)
            flash("Grabando track")
        }
    }

    private func newPlacemark(_ lat: Double, _ lon: Double) {
        draft = PlacemarkDraft(id: UUID().uuidString, existing: false,
                               name: "Punto \(store.placemarks.count + 1)", note: "",
                               lat: lat, lon: lon, colorHex: "E53935", time: Date())
    }

    private func export() {
        let pms = store.placemarks(for: map), trks = store.tracks(for: map)
        guard !pms.isEmpty || !trks.isEmpty else { flash("No hay marcadores ni tracks para exportar"); return }
        shareItems = KmlExporter.write(title: map.name, placemarks: pms, tracks: trks)
    }

    private func flash(_ s: String) {
        withAnimation { toast = s }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { withAnimation { if toast == s { toast = nil } } }
    }

    // MARK: listas

    private var placemarkList: some View {
        NavigationStack {
            List {
                ForEach(store.placemarks(for: map)) { p in
                    Button {
                        showPlacemarks = false
                        ctl.follow = false
                        ctl.canvas.crosshair = crosshairVisible
                        ctl.canvas.centerOn(lat: p.lat, lon: p.lon, zoomIn: true)
                    } label: {
                        HStack {
                            Circle().fill(Color(uiColor: UIColor(hex: p.colorHex))).frame(width: 12, height: 12)
                            VStack(alignment: .leading) {
                                Text(p.name.isEmpty ? "(sin nombre)" : p.name).foregroundStyle(.primary)
                                if !p.note.isEmpty { Text(p.note).font(.caption).foregroundStyle(.secondary) }
                            }
                            Spacer()
                            if let l = gps.location {
                                Text(GeoMath.formatDistance(GeoMath.distance(l.coordinate.latitude, l.coordinate.longitude, p.lat, p.lon)))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .onDelete { idx in
                    let list = store.placemarks(for: map)
                    idx.forEach { store.deletePlacemark(list[$0].id) }
                }
            }
            .navigationTitle("Marcadores")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Cerrar") { showPlacemarks = false } } }
        }
    }

    private var trackList: some View {
        NavigationStack {
            List {
                ForEach(store.tracks(for: map)) { t in
                    Button {
                        showTracks = false
                        if let p = t.points.first {
                            ctl.follow = false
                            ctl.canvas.crosshair = crosshairVisible
                            ctl.canvas.centerOn(lat: p.lat, lon: p.lon, zoomIn: true)
                        }
                    } label: {
                        VStack(alignment: .leading) {
                            Text(t.name).foregroundStyle(.primary)
                            Text(GeoMath.formatDistance(t.length) + " · \(t.points.count) puntos").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { idx in
                    let list = store.tracks(for: map)
                    idx.forEach { store.deleteTrack(list[$0].id) }
                }
            }
            .navigationTitle("Tracks")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Cerrar") { showTracks = false } } }
        }
    }
}

/// Editor de marcador.
struct PlacemarkEditor: View {
    @State var draft: PlacemarkDraft
    let mapId: String
    @Environment(\.dismiss) private var dismiss
    private let colors = ["E53935", "E8A33D", "43A047", "1E88E5", "8E24AA"]

    var body: some View {
        NavigationStack {
            Form {
                TextField("Nombre", text: $draft.name)
                TextField("Nota (ej.: daño observado, especie, foco)", text: $draft.note, axis: .vertical)
                    .lineLimit(2...5)
                Section("Color") {
                    HStack(spacing: 18) {
                        ForEach(colors, id: \.self) { c in
                            Circle().fill(Color(uiColor: UIColor(hex: c)))
                                .frame(width: 32, height: 32)
                                .opacity(draft.colorHex == c ? 1 : 0.35)
                                .onTapGesture { draft.colorHex = c }
                        }
                    }
                }
                Section("Ubicación") {
                    Text("UTM " + GeoMath.formatUTM(draft.lat, draft.lon)).font(.system(.footnote, design: .monospaced))
                    Text(GeoMath.formatLatLon(draft.lat, draft.lon)).font(.system(.footnote, design: .monospaced))
                }
                if draft.existing {
                    Section {
                        Button("Eliminar marcador", role: .destructive) {
                            Store.shared.deletePlacemark(draft.id); dismiss()
                        }
                    }
                }
            }
            .navigationTitle(draft.existing ? "Marcador" : "Nuevo marcador")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancelar") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Guardar") {
                        Store.shared.upsert(Placemark(id: draft.id, mapId: mapId, name: draft.name.trimmingCharacters(in: .whitespaces),
                                                      note: draft.note.trimmingCharacters(in: .whitespacesAndNewlines),
                                                      lat: draft.lat, lon: draft.lon, time: draft.time, colorHex: draft.colorHex))
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
