import UIKit
import CoreLocation

enum PDFRender {
    /// Página completa como imagen, lado mayor = maxPixels.
    static func image(url: URL, maxPixels: CGFloat) -> UIImage? {
        guard let doc = CGPDFDocument(url as CFURL), let page = doc.page(at: 1) else { return nil }
        let box = page.getBoxRect(.cropBox)
        let s = maxPixels / max(box.width, box.height)
        let size = CGSize(width: max(1, box.width * s), height: max(1, box.height * s))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let c = ctx.cgContext
            c.translateBy(x: 0, y: size.height)
            c.scaleBy(x: s, y: -s)
            c.translateBy(x: -box.minX, y: -box.minY)
            c.drawPDFPage(page)
        }
    }
}

/// Vista con CATiledLayer: dibuja el PDF por mosaicos, nítido a cualquier zoom.
final class TiledPDFView: UIView {
    override class var layerClass: AnyClass { CATiledLayer.self }
    private let page: CGPDFPage
    private let box: CGRect

    init(page: CGPDFPage) {
        self.page = page
        self.box = page.getBoxRect(.cropBox)
        super.init(frame: CGRect(origin: .zero, size: box.size))
        backgroundColor = .clear
        isOpaque = false
        let tiled = layer as! CATiledLayer
        tiled.levelsOfDetail = 4
        tiled.levelsOfDetailBias = 6
        tiled.tileSize = CGSize(width: 512, height: 512)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        UIColor.white.setFill()
        c.fill(rect)
        c.saveGState()
        c.translateBy(x: 0, y: bounds.height)
        c.scaleBy(x: 1, y: -1)
        c.translateBy(x: -box.minX, y: -box.minY)
        c.drawPDFPage(page)
        c.restoreGState()
    }
}

/// Visor del plano con capas (ubicación, marcadores, tracks, medición, mira).
/// Lo usan la pantalla del teléfono y la de CarPlay.
final class MapCanvasView: UIView, UIScrollViewDelegate {

    private(set) var map: MapEntry?
    private let scroll = UIScrollView()
    private let content = UIView()
    private let baseImage = UIImageView()
    private var tiled: TiledPDFView?
    private let overlay = OverlayView()
    private var fitted = false
    private var lastCenter: (Double, Double)?

    var location: CLLocation? { didSet { overlay.setNeedsDisplay() } }
    var placemarks: [Placemark] = [] { didSet { overlay.setNeedsDisplay() } }
    var tracks: [Track] = [] { didSet { overlay.setNeedsDisplay() } }
    var activeTrack: Track? { didSet { overlay.setNeedsDisplay() } }
    var measure: [(Double, Double)] = [] { didSet { overlay.setNeedsDisplay() } }
    var crosshair = false { didSet { lastCenter = nil; overlay.setNeedsDisplay(); reportCenter() } }
    var statusText: String? { didSet { overlay.setNeedsDisplay() } }
    /// Zona libre de la pantalla (CarPlay: área sin botones).
    var safeFrame: CGRect? { didSet { overlay.setNeedsDisplay() } }

    var onCenterChanged: ((Double, Double) -> Void)?
    var onUserMoved: (() -> Void)?
    var onTapGeo: ((Double, Double) -> Bool)?
    var onLongPressGeo: ((Double, Double) -> Void)?
    var onPlacemarkTap: ((Placemark) -> Void)?

    var interactive = true {
        didSet { scroll.isScrollEnabled = interactive; scroll.pinchGestureRecognizer?.isEnabled = interactive }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(white: 0.87, alpha: 1)
        scroll.delegate = self
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.decelerationRate = .normal
        scroll.bouncesZoom = true
        scroll.contentInsetAdjustmentBehavior = .never
        addSubview(scroll)
        scroll.addSubview(content)
        content.addSubview(baseImage)
        overlay.canvas = self
        overlay.isUserInteractionEnabled = false
        overlay.backgroundColor = .clear
        overlay.contentMode = .redraw
        addSubview(overlay)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.require(toFail: doubleTap)
        let long = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:)))
        scroll.addGestureRecognizer(doubleTap)
        scroll.addGestureRecognizer(tap)
        scroll.addGestureRecognizer(long)
    }

    required init?(coder: NSCoder) { fatalError() }

    func setMap(_ m: MapEntry, url: URL) {
        map = m
        tiled?.removeFromSuperview()
        tiled = nil
        fitted = false
        let size = CGSize(width: m.pageWidth, height: m.pageHeight)
        content.frame = CGRect(origin: .zero, size: size)
        baseImage.frame = content.bounds
        baseImage.image = nil
        scroll.zoomScale = 1
        scroll.contentSize = size
        if let doc = CGPDFDocument(url as CFURL), let page = doc.page(at: 1) {
            let t = TiledPDFView(page: page)
            t.frame = content.bounds
            content.addSubview(t)
            tiled = t
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let img = PDFRender.image(url: url, maxPixels: 2048)
            DispatchQueue.main.async { if self.map?.id == m.id { self.baseImage.image = img } }
        }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        scroll.frame = bounds
        overlay.frame = bounds
        let inset = UIEdgeInsets(top: bounds.height / 2, left: bounds.width / 2,
                                 bottom: bounds.height / 2, right: bounds.width / 2)
        scroll.contentInset = inset
        if !fitted, map != nil, bounds.width > 0 { fit() }
        overlay.setNeedsDisplay()
    }

    private var visibleFrame: CGRect { safeFrame ?? bounds }

    /// Ajusta el marco del mapa a la pantalla.
    func fit() {
        guard let m = map, bounds.width > 0 else { return }
        let n = m.neat
        let target = CGRect(x: n[0], y: n[1], width: n[2] - n[0], height: n[3] - n[1])
        let v = visibleFrame
        let s = min(v.width / target.width, v.height / target.height)
        let pageFit = min(v.width / CGFloat(m.pageWidth), v.height / CGFloat(m.pageHeight))
        scroll.minimumZoomScale = pageFit * 0.5
        scroll.maximumZoomScale = s * 60
        scroll.zoomScale = s
        center(pagePoint: CGPoint(x: target.midX, y: target.midY), animated: false)
        fitted = true
    }

    var currentZoom: CGFloat { scroll.zoomScale }

    private func center(pagePoint p: CGPoint, animated: Bool) {
        let v = visibleFrame
        let off = CGPoint(x: p.x * scroll.zoomScale - v.midX, y: p.y * scroll.zoomScale - v.midY)
        scroll.setContentOffset(off, animated: animated)
        overlay.setNeedsDisplay()
        reportCenter()
    }

    func centerOn(lat: Double, lon: Double, zoomIn: Bool = false, animated: Bool = false) {
        guard let t = map?.transform else { return }
        if zoomIn, let m = map {
            let target = max(scroll.zoomScale, min(scroll.maximumZoomScale, bounds.width / CGFloat(m.pageWidth) * 4))
            scroll.zoomScale = target
        }
        center(pagePoint: t.toPage(lat: lat, lon: lon), animated: animated)
    }

    func zoom(by f: CGFloat) {
        let v = visibleFrame
        let centerPage = screenToPage(CGPoint(x: v.midX, y: v.midY))
        scroll.zoomScale = min(scroll.maximumZoomScale, max(scroll.minimumZoomScale, scroll.zoomScale * f))
        center(pagePoint: centerPage, animated: false)
    }

    func pan(dx: CGFloat, dy: CGFloat) {
        var o = scroll.contentOffset
        o.x += dx; o.y += dy
        scroll.setContentOffset(o, animated: false)
        overlay.setNeedsDisplay()
        reportCenter()
    }

    // MARK: conversión de coordenadas

    func pageToScreen(_ p: CGPoint) -> CGPoint { content.convert(p, to: overlay) }
    func screenToPage(_ p: CGPoint) -> CGPoint { overlay.convert(p, to: content) }

    func geoToScreen(_ lat: Double, _ lon: Double) -> CGPoint? {
        guard let t = map?.transform else { return nil }
        return pageToScreen(t.toPage(lat: lat, lon: lon))
    }

    func screenToGeo(_ p: CGPoint) -> (Double, Double)? {
        guard let t = map?.transform else { return nil }
        let g = t.toGeo(screenToPage(p))
        return (g.lat, g.lon)
    }

    var crosshairPoint: CGPoint { CGPoint(x: visibleFrame.midX, y: visibleFrame.midY) }

    private func reportCenter() {
        guard crosshair, let c = screenToGeo(crosshairPoint) else { return }
        if let l = lastCenter, l.0 == c.0, l.1 == c.1 { return }
        lastCenter = c
        onCenterChanged?(c.0, c.1)
    }

    // MARK: UIScrollViewDelegate

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { content }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        overlay.setNeedsDisplay()
        if scrollView.isDragging || scrollView.isDecelerating { onUserMoved?() }
        reportCenter()
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        overlay.setNeedsDisplay()
        if scrollView.isZooming { onUserMoved?() }
        reportCenter()
    }

    // MARK: gestos

    @objc private func doubleTapped(_ g: UITapGestureRecognizer) {
        let p = g.location(in: overlay)
        let page = screenToPage(p)
        let newZoom = min(scroll.maximumZoomScale, scroll.zoomScale * 2)
        let w = scroll.bounds.width / newZoom, h = scroll.bounds.height / newZoom
        scroll.zoom(to: CGRect(x: page.x - w / 2, y: page.y - h / 2, width: w, height: h), animated: true)
        onUserMoved?()
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let p = g.location(in: overlay)
        guard let geo = screenToGeo(p) else { return }
        if onTapGeo?(geo.0, geo.1) == true { return }
        let hit = placemarks.last(where: { pm in
            guard let s = geoToScreen(pm.lat, pm.lon) else { return false }
            return hypot(s.x - p.x, s.y - 16 - p.y) < 24
        })
        if let hit { onPlacemarkTap?(hit) }
    }

    @objc private func longPressed(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began, let geo = screenToGeo(g.location(in: overlay)) else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        onLongPressGeo?(geo.0, geo.1)
    }
}

/// Capa de dibujo en coordenadas de pantalla.
final class OverlayView: UIView {
    weak var canvas: MapCanvasView?

    override func draw(_ rect: CGRect) {
        guard let cv = canvas, let m = cv.map, let t = m.transform,
              let c = UIGraphicsGetCurrentContext() else { return }

        func path(_ pts: [(Double, Double)]) -> UIBezierPath {
            let p = UIBezierPath()
            for (i, g) in pts.enumerated() {
                guard let s = cv.geoToScreen(g.0, g.1) else { continue }
                if i == 0 { p.move(to: s) } else { p.addLine(to: s) }
            }
            return p
        }

        // tracks
        for tr in cv.tracks where tr.points.count > 1 {
            let p = path(tr.points.map { ($0.lat, $0.lon) })
            p.lineWidth = 4; p.lineCapStyle = .round; p.lineJoinStyle = .round
            UIColor(red: 0.91, green: 0.56, blue: 0.24, alpha: 1).setStroke(); p.stroke()
        }
        if let a = cv.activeTrack, a.points.count > 1 {
            let p = path(a.points.map { ($0.lat, $0.lon) })
            p.lineWidth = 4; p.lineCapStyle = .round; p.lineJoinStyle = .round
            UIColor(red: 0.83, green: 0.18, blue: 0.18, alpha: 1).setStroke(); p.stroke()
        }

        // medición
        if !cv.measure.isEmpty {
            let purple = UIColor(red: 0.61, green: 0.15, blue: 0.69, alpha: 1)
            let p = path(cv.measure)
            if cv.measure.count >= 3 {
                let closed = p.copy() as! UIBezierPath
                closed.close()
                purple.withAlphaComponent(0.2).setFill(); closed.fill()
            }
            p.lineWidth = 3; p.setLineDash([12, 6], count: 2, phase: 0)
            purple.setStroke(); p.stroke()
            for g in cv.measure {
                guard let s = cv.geoToScreen(g.0, g.1) else { continue }
                UIColor.white.setFill(); UIBezierPath(arcCenter: s, radius: 6, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
                purple.setFill(); UIBezierPath(arcCenter: s, radius: 4, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
            }
        }

        // marcadores
        let labelAttrs: [NSAttributedString.Key: Any] = [
            .font: UIFont.boldSystemFont(ofSize: 12), .foregroundColor: UIColor.black,
            .strokeColor: UIColor.white, .strokeWidth: -3.0
        ]
        for pm in cv.placemarks {
            guard let s = cv.geoToScreen(pm.lat, pm.lon), bounds.insetBy(dx: -40, dy: -40).contains(s) else { continue }
            let col = UIColor(hex: pm.colorHex)
            let r: CGFloat = 9
            let head = CGPoint(x: s.x, y: s.y - r * 1.8)
            UIColor.white.setFill(); UIBezierPath(arcCenter: head, radius: r + 2, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
            let tri = UIBezierPath()
            tri.move(to: s); tri.addLine(to: CGPoint(x: s.x - r * 0.75, y: s.y - r * 1.4)); tri.addLine(to: CGPoint(x: s.x + r * 0.75, y: s.y - r * 1.4)); tri.close()
            col.setFill(); tri.fill()
            UIBezierPath(arcCenter: head, radius: r, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
            UIColor.white.setFill(); UIBezierPath(arcCenter: head, radius: r * 0.38, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
            if !pm.name.isEmpty {
                (pm.name as NSString).draw(at: CGPoint(x: s.x + r * 1.3, y: s.y - r * 2.6), withAttributes: labelAttrs)
            }
        }

        let center = cv.crosshairPoint

        // ubicación + línea guía a la mira
        if let loc = cv.location, let s = cv.geoToScreen(loc.coordinate.latitude, loc.coordinate.longitude) {
            if cv.crosshair {
                let g = UIBezierPath(); g.move(to: s); g.addLine(to: center)
                g.lineWidth = 2.5; g.setLineDash([10, 8], count: 2, phase: 0)
                UIColor(red: 0.12, green: 0.53, blue: 0.9, alpha: 0.8).setStroke(); g.stroke()
            }
            if loc.horizontalAccuracy > 0 {
                let rad = CGFloat(loc.horizontalAccuracy * t.pagePerMeter(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)) * cv.currentZoom
                if rad > 10 {
                    let a = UIBezierPath(arcCenter: s, radius: rad, startAngle: 0, endAngle: .pi * 2, clockwise: true)
                    UIColor(red: 0.12, green: 0.53, blue: 0.9, alpha: 0.18).setFill(); a.fill()
                    UIColor(red: 0.12, green: 0.53, blue: 0.9, alpha: 0.55).setStroke(); a.lineWidth = 1.5; a.stroke()
                }
            }
            if loc.course >= 0, loc.speed > 0.8 {
                let ang = loc.course * .pi / 180 + t.northAngle(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude)
                let len: Double = 34, half = 28.0 * .pi / 180
                let cone = UIBezierPath()
                cone.move(to: s)
                cone.addLine(to: CGPoint(x: s.x + CGFloat(len * sin(ang - half)), y: s.y - CGFloat(len * cos(ang - half))))
                cone.addLine(to: CGPoint(x: s.x + CGFloat(len * sin(ang + half)), y: s.y - CGFloat(len * cos(ang + half))))
                cone.close()
                UIColor(red: 0.12, green: 0.53, blue: 0.9, alpha: 0.45).setFill(); cone.fill()
            }
            UIColor.white.setFill(); UIBezierPath(arcCenter: s, radius: 10, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
            UIColor(red: 0.12, green: 0.53, blue: 0.9, alpha: 1).setFill(); UIBezierPath(arcCenter: s, radius: 7, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
        }

        // mira central
        if cv.crosshair {
            let r: CGFloat = 30, tick: CGFloat = 11
            for (color, width) in [(UIColor.white.withAlphaComponent(0.7), CGFloat(7)), (UIColor(white: 0.43, alpha: 1), CGFloat(3.5))] {
                let p = UIBezierPath(arcCenter: center, radius: r, startAngle: 0, endAngle: .pi * 2, clockwise: true)
                p.move(to: CGPoint(x: center.x, y: center.y - r - tick * 0.35)); p.addLine(to: CGPoint(x: center.x, y: center.y - r + tick))
                p.move(to: CGPoint(x: center.x, y: center.y + r + tick * 0.35)); p.addLine(to: CGPoint(x: center.x, y: center.y + r - tick))
                p.move(to: CGPoint(x: center.x - r - tick * 0.35, y: center.y)); p.addLine(to: CGPoint(x: center.x - r + tick, y: center.y))
                p.move(to: CGPoint(x: center.x + r + tick * 0.35, y: center.y)); p.addLine(to: CGPoint(x: center.x + r - tick, y: center.y))
                p.lineWidth = width; p.lineCapStyle = .round
                color.setStroke(); p.stroke()
            }
            UIColor.white.setFill(); UIBezierPath(arcCenter: center, radius: 4.5, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
            UIColor(white: 0.43, alpha: 1).setFill(); UIBezierPath(arcCenter: center, radius: 3, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
        }

        // texto de estado (CarPlay)
        if let text = cv.statusText, !text.isEmpty {
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 15), .foregroundColor: UIColor.white]
            let size = (text as NSString).size(withAttributes: attrs)
            let origin = CGPoint(x: (cv.safeFrame ?? bounds).minX + 10, y: (cv.safeFrame ?? bounds).maxY - size.height - 22)
            let bg = UIBezierPath(roundedRect: CGRect(x: origin.x - 8, y: origin.y - 6, width: size.width + 16, height: size.height + 12), cornerRadius: 8)
            UIColor(red: 0.12, green: 0.36, blue: 0.23, alpha: 0.92).setFill(); bg.fill()
            (text as NSString).draw(at: origin, withAttributes: attrs)
        }
        _ = c
    }
}
