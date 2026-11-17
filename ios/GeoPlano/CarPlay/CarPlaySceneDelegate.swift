import UIKit
import CarPlay
import Combine

/// Pantalla del auto: el plano se dibuja en la ventana de CarPlay (categoría mapas).
final class CarMapViewController: UIViewController {
    let canvas = MapCanvasView(frame: .zero)

    override func loadView() {
        canvas.interactive = false
        view = canvas
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        canvas.safeFrame = view.bounds.inset(by: view.safeAreaInsets)
    }
}

final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate, CPMapTemplateDelegate {

    static var isConnected = false

    private var interfaceController: CPInterfaceController?
    private var carWindow: CPWindow?
    private let mapVC = CarMapViewController()
    private let mapTemplate = CPMapTemplate()
    private var current: MapEntry?
    private var follow = true
    private var panning = false
    private var lastPan = CGPoint.zero
    private var bag = Set<AnyCancellable>()

    private var canvas: MapCanvasView { mapVC.canvas }

    // MARK: conexión

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController,
                                  to window: CPWindow) {
        Self.isConnected = true
        self.interfaceController = interfaceController
        carWindow = window
        window.rootViewController = mapVC
        mapTemplate.mapDelegate = self
        mapTemplate.automaticallyHidesNavigationBar = false
        refreshButtons()
        interfaceController.setRootTemplate(mapTemplate, animated: false, completion: nil)
        LocationService.shared.start()
        subscribe()
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnect interfaceController: CPInterfaceController,
                                  from window: CPWindow) {
        Self.isConnected = false
        bag.removeAll()
        self.interfaceController = nil
        carWindow = nil
    }

    // MARK: datos

    private func subscribe() {
        let store = Store.shared
        store.$maps.combineLatest(store.$selectedMapId)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] maps, sel in self?.chooseMap(maps, sel) }
            .store(in: &bag)
        LocationService.shared.$location
            .receive(on: DispatchQueue.main)
            .sink { [weak self] loc in
                guard let self else { return }
                self.canvas.location = loc
                if self.follow, let loc, let m = self.current,
                   m.contains(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude) {
                    self.canvas.centerOn(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
                }
                self.updateStatus()
            }
            .store(in: &bag)
        store.$placemarks.receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.refreshLayers() }.store(in: &bag)
        store.$tracks.receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.refreshLayers() }.store(in: &bag)
        TrackRecorder.shared.$active.receive(on: DispatchQueue.main)
            .sink { [weak self] t in
                guard let self else { return }
                let changed = (self.canvas.activeTrack == nil) != (t == nil)
                self.canvas.activeTrack = t
                if changed { self.refreshButtons() }
            }
            .store(in: &bag)
        canvas.onCenterChanged = { [weak self] _, _ in self?.updateStatus() }
    }

    private func chooseMap(_ maps: [MapEntry], _ selected: String?) {
        let loc = LocationService.shared.location
        let chosen = maps.first { $0.id == selected }
            ?? loc.flatMap { l in maps.first { $0.contains(lat: l.coordinate.latitude, lon: l.coordinate.longitude) } }
            ?? maps.max { $0.importedAt < $1.importedAt }
        guard chosen?.id != current?.id else { return }
        current = chosen
        follow = true
        canvas.crosshair = false
        if let m = chosen {
            canvas.setMap(m, url: Store.shared.pdfURL(m))
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.canvas.fit()
                if let l = loc, m.contains(lat: l.coordinate.latitude, lon: l.coordinate.longitude) {
                    self.canvas.centerOn(lat: l.coordinate.latitude, lon: l.coordinate.longitude, zoomIn: true)
                }
            }
        }
        refreshLayers()
        updateStatus()
    }

    private func refreshLayers() {
        guard let m = current else { canvas.placemarks = []; canvas.tracks = []; return }
        canvas.placemarks = Store.shared.placemarks(for: m)
        canvas.tracks = Store.shared.tracks.filter { $0.mapId == m.id }
    }

    private func updateStatus() {
        guard let m = current else {
            canvas.statusText = Store.shared.maps.isEmpty ? "Importa tus planos PDF en GeoPlano del iPhone" : "Elige un plano en “Planos”"
            return
        }
        let loc = LocationService.shared.location
        var s = m.name + " · "
        if !m.isGeoreferenced {
            s += "sin georreferencia"
        } else if !follow, let c = canvas.screenToGeo(canvas.crosshairPoint), let l = loc {
            let d = GeoMath.distance(l.coordinate.latitude, l.coordinate.longitude, c.0, c.1)
            let b = GeoMath.bearing(l.coordinate.latitude, l.coordinate.longitude, c.0, c.1)
            s += "Mira: " + GeoMath.formatDistance(d) + " al " + GeoMath.cardinal(b)
        } else if !LocationService.shared.hasPermission {
            s += "concede la ubicación en el iPhone"
        } else if let l = loc {
            if m.contains(lat: l.coordinate.latitude, lon: l.coordinate.longitude) {
                s += "Estás dentro del plano"
            } else if let c = m.center() {
                s += "Fuera del plano · " + GeoMath.formatDistance(GeoMath.distance(l.coordinate.latitude, l.coordinate.longitude, c.lat, c.lon))
            }
        } else {
            s += "Buscando GPS…"
        }
        canvas.statusText = s
    }

    // MARK: botones

    private func refreshButtons() {
        if panning {
            mapTemplate.leadingNavigationBarButtons = [CPBarButton(title: "Listo") { [weak self] _ in
                self?.mapTemplate.dismissPanningInterface(animated: true)
            }]
            mapTemplate.trailingNavigationBarButtons = []
            mapTemplate.mapButtons = []
            return
        }
        let recording = TrackRecorder.shared.isRecording
        mapTemplate.leadingNavigationBarButtons = [CPBarButton(title: "Planos") { [weak self] _ in self?.showMapList() }]
        mapTemplate.trailingNavigationBarButtons = [CPBarButton(title: recording ? "Detener" : "Grabar") { [weak self] _ in
            self?.toggleTrack()
        }]

        let recenter = CPMapButton { [weak self] _ in
            guard let self, let l = LocationService.shared.location else { return }
            self.follow = true
            self.canvas.crosshair = false
            self.canvas.centerOn(lat: l.coordinate.latitude, lon: l.coordinate.longitude, zoomIn: true)
            self.updateStatus()
        }
        recenter.image = UIImage(systemName: "location.fill")
        let zoomIn = CPMapButton { [weak self] _ in self?.canvas.zoom(by: 1.6) }
        zoomIn.image = UIImage(systemName: "plus.magnifyingglass")
        let zoomOut = CPMapButton { [weak self] _ in self?.canvas.zoom(by: 1 / 1.6) }
        zoomOut.image = UIImage(systemName: "minus.magnifyingglass")
        let pan = CPMapButton { [weak self] _ in self?.mapTemplate.showPanningInterface(animated: true) }
        pan.image = UIImage(systemName: "arrow.up.and.down.and.arrow.left.and.right")
        mapTemplate.mapButtons = [recenter, zoomIn, zoomOut, pan]
    }

    private func toggleTrack() {
        if TrackRecorder.shared.isRecording {
            TrackRecorder.shared.stop()
        } else if LocationService.shared.hasPermission {
            TrackRecorder.shared.start(mapId: current?.id)
        }
        refreshButtons()
    }

    private func showMapList() {
        let loc = LocationService.shared.location
        let maps = Store.shared.sorted(near: loc?.coordinate.latitude, loc?.coordinate.longitude)
        let items: [CPListItem] = maps.prefix(CPListTemplate.maximumItemCount).map { m in
            var sub = m.projection ?? "Georreferenciado"
            if !m.isGeoreferenced { sub = "Sin georreferencia" }
            else if let l = loc {
                if m.contains(lat: l.coordinate.latitude, lon: l.coordinate.longitude) { sub = "● Estás aquí" }
                else if let c = m.center() {
                    sub = "a " + GeoMath.formatDistance(GeoMath.distance(l.coordinate.latitude, l.coordinate.longitude, c.lat, c.lon))
                }
            }
            let item = CPListItem(text: (m.id == current?.id ? "✓ " : "") + m.name, detailText: sub)
            item.handler = { [weak self] _, completion in
                Store.shared.selectedMapId = m.id
                self?.interfaceController?.popTemplate(animated: true, completion: nil)
                completion()
            }
            return item
        }
        let template = CPListTemplate(title: "Planos", sections: [CPListSection(items: items)])
        template.emptyViewTitleVariants = ["No hay planos importados"]
        interfaceController?.pushTemplate(template, animated: true, completion: nil)
    }

    // MARK: CPMapTemplateDelegate (desplazamiento)

    func mapTemplateDidShowPanningInterface(_ mapTemplate: CPMapTemplate) {
        panning = true
        follow = false
        canvas.crosshair = true
        refreshButtons()
        updateStatus()
    }

    func mapTemplateDidDismissPanningInterface(_ mapTemplate: CPMapTemplate) {
        panning = false
        refreshButtons()
        updateStatus()
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, panWith direction: CPMapTemplate.PanDirection) {
        let step = min(canvas.bounds.width, canvas.bounds.height) * 0.25
        var dx: CGFloat = 0, dy: CGFloat = 0
        if direction.contains(.left) { dx -= step }
        if direction.contains(.right) { dx += step }
        if direction.contains(.up) { dy -= step }
        if direction.contains(.down) { dy += step }
        moveBy(dx, dy)
    }

    func mapTemplateDidBeginPanGesture(_ mapTemplate: CPMapTemplate) {
        lastPan = .zero
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, didUpdatePanGestureWithTranslation translation: CGPoint, velocity: CGPoint) {
        moveBy(-(translation.x - lastPan.x), -(translation.y - lastPan.y))
        lastPan = translation
    }

    func mapTemplate(_ mapTemplate: CPMapTemplate, didEndPanGestureWithVelocity velocity: CGPoint) {
        lastPan = .zero
    }

    private func moveBy(_ dx: CGFloat, _ dy: CGFloat) {
        follow = false
        canvas.crosshair = true
        canvas.pan(dx: dx, dy: dy)
        updateStatus()
    }
}
