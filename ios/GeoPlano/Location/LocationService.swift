import Foundation
import CoreLocation
import Combine

/// GPS compartido entre el teléfono, CarPlay y la grabación de tracks.
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    @Published private(set) var location: CLLocation?
    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined

    private let manager = CLLocationManager()
    private var running = false

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = false
        authorization = manager.authorizationStatus
    }

    var hasPermission: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    func start() {
        if authorization == .notDetermined { manager.requestWhenInUseAuthorization() }
        guard !running else { return }
        running = true
        manager.startUpdatingLocation()
    }

    /// Se detiene al pasar a segundo plano, salvo que se esté grabando un track o CarPlay esté activo.
    func stopIfIdle(carPlayActive: Bool) {
        guard running, !TrackRecorder.shared.isRecording, !carPlayActive else { return }
        running = false
        manager.stopUpdatingLocation()
    }

    func setBackground(_ enabled: Bool) {
        manager.allowsBackgroundLocationUpdates = enabled
        manager.showsBackgroundLocationIndicator = enabled
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        if hasPermission && running { manager.startUpdatingLocation() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, loc.horizontalAccuracy >= 0 else { return }
        location = loc
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

/// Track en grabación, compartido entre teléfono y CarPlay.
final class TrackRecorder: ObservableObject {
    static let shared = TrackRecorder()

    @Published private(set) var active: Track?
    private var sub: AnyCancellable?

    var isRecording: Bool { active != nil }

    func start(mapId: String?) {
        guard active == nil else { return }
        let f = DateFormatter(); f.dateFormat = "dd-MM-yyyy HH:mm"
        active = Track(id: UUID().uuidString, mapId: mapId, name: "Track " + f.string(from: Date()), points: [])
        LocationService.shared.setBackground(true)
        LocationService.shared.start()
        sub = LocationService.shared.$location.compactMap { $0 }.sink { [weak self] in self?.add($0) }
    }

    @discardableResult
    func stop() -> Track? {
        sub = nil
        LocationService.shared.setBackground(false)
        guard let t = active else { return nil }
        active = nil
        if t.points.count >= 2 { Store.shared.saveTrack(t) } else { Store.shared.deleteTrack(t.id) }
        return t
    }

    private func add(_ loc: CLLocation) {
        guard var t = active, loc.horizontalAccuracy <= 30 else { return }
        if let last = t.points.last,
           GeoMath.distance(last.lat, last.lon, loc.coordinate.latitude, loc.coordinate.longitude) < 3 { return }
        t.points.append(TrackPoint(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                                   time: loc.timestamp, alt: loc.verticalAccuracy >= 0 ? loc.altitude : nil))
        active = t
        if t.points.count % 20 == 0 { Store.shared.saveTrack(t) }
    }
}
