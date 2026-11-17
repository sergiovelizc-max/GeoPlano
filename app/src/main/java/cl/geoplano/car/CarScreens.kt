package cl.geoplano.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cl.geoplano.R
import cl.geoplano.data.MapEntry
import cl.geoplano.data.MapStore
import cl.geoplano.geo.GeoMath
import cl.geoplano.location.LocationRepository
import cl.geoplano.track.TrackRecorder
import cl.geoplano.track.TrackingService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Punto de entrada de Android Auto. */
class GeoPlanoCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = GeoPlanoSession()
}

class GeoPlanoSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        MapStore.init(carContext)
        return CarMapScreen(carContext)
    }
}

/** Pantalla principal en el auto: el plano en la superficie + barra de acciones. */
class CarMapScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CarMapRenderer(carContext, lifecycleScope)
    private var current: MapEntry? = null
    private var statusText = ""
    private var inPanMode = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        if (!LocationRepository.hasPermission(carContext)) {
            runCatching {
                carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                    if (granted.isNotEmpty()) LocationRepository.restart(carContext)
                }
            }
        }
        LocationRepository.acquire(carContext)

        lifecycleScope.launch {
            combine(MapStore.maps, MapStore.selectedMapId) { maps, sel -> maps to sel }.collect { (maps, sel) ->
                val loc = LocationRepository.location.value
                val chosen = maps.firstOrNull { it.id == sel }
                    ?: loc?.let { l -> maps.firstOrNull { it.contains(l.latitude, l.longitude) } }
                    ?: maps.maxByOrNull { it.importedAt }
                if (chosen?.id != current?.id) {
                    current = chosen
                    renderer.follow = true
                    renderer.setMap(chosen)
                    refreshPins()
                    updateStatus(force = true)
                }
            }
        }
        lifecycleScope.launch {
            LocationRepository.location.collect { loc ->
                renderer.location = loc
                updateStatus(force = false)
            }
        }
        lifecycleScope.launch { MapStore.placemarks.collect { refreshPins() } }
        lifecycleScope.launch { MapStore.tracks.collect { refreshPins() } }
        lifecycleScope.launch {
            TrackRecorder.active.collect { t ->
                val wasRecording = renderer.activeTrack != null
                renderer.activeTrack = t
                if (wasRecording != (t != null)) invalidate()
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        LocationRepository.release()
    }

    private fun refreshPins() {
        val m = current
        renderer.placemarks = if (m == null) emptyList() else MapStore.placemarks.value.filter { it.mapId == m.id || m.contains(it.lat, it.lon) }
        renderer.tracks = if (m == null) emptyList() else MapStore.tracks.value.filter { it.mapId == m.id }
    }

    /** Texto del recuadro superior; sólo se refresca la plantilla si cambia. */
    private fun updateStatus(force: Boolean) {
        val m = current
        val loc = LocationRepository.location.value
        val s = when {
            m == null -> ""
            !m.isGeoreferenced -> "Plano sin georreferencia"
            !LocationRepository.hasPermission(carContext) -> "Concede la ubicación en el teléfono"
            loc == null -> "Buscando GPS…"
            m.contains(loc.latitude, loc.longitude) -> "Estás dentro del plano"
            else -> m.center()?.let { "Fuera del plano · " + GeoMath.formatDistance(roundKm(GeoMath.distance(loc.latitude, loc.longitude, it[0], it[1]))) } ?: "Fuera del plano"
        }
        if (force || s != statusText) {
            statusText = s
            invalidate()
        }
    }

    /** Evita refrescar la plantilla a cada metro: redondea la distancia. */
    private fun roundKm(d: Double) = if (d < 1000) Math.round(d / 100.0) * 100.0 else Math.round(d / 1000.0) * 1000.0

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        if (MapStore.maps.value.isEmpty()) {
            return MessageTemplate.Builder("Importa tus planos PDF desde la app GeoPlano en el teléfono y aparecerán aquí.")
                .setTitle("GeoPlano")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val recording = TrackRecorder.isRecording
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Planos")
                    .setOnClickListener { screenManager.push(MapListCarScreen(carContext)) }
                    .build()
            )
            .addAction(
                Action.Builder().setTitle(if (recording) "Detener" else "Grabar")
                    .setIcon(icon(if (recording) R.drawable.ic_stop else R.drawable.ic_record))
                    .setOnClickListener { toggleTrack() }
                    .build()
            )
            .build()

        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_my_location)).setOnClickListener {
                renderer.follow = true
                renderer.centerOnLocation(true)
            }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_in)).setOnClickListener { renderer.zoom(1.6f) }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_zoom_out)).setOnClickListener { renderer.zoom(1 / 1.6f) }.build())
            .build()

        val builder = NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapStrip)
            .setBackgroundColor(CarColor.createCustom(0xFF1F5C3A.toInt(), 0xFF1F5C3A.toInt()))
            .setPanModeListener { isInPanMode -> inPanMode = isInPanMode; if (isInPanMode) renderer.follow = false }

        val m = current
        if (m != null && !inPanMode) {
            builder.setNavigationInfo(MessageInfo.Builder(m.name).setText(statusText).build())
        }
        return builder.build()
    }

    private fun toggleTrack() {
        if (TrackRecorder.isRecording) {
            TrackingService.stop(carContext)
            CarToast.makeText(carContext, "Track guardado", CarToast.LENGTH_SHORT).show()
        } else {
            val ok = LocationRepository.hasPermission(carContext) && TrackingService.start(carContext, current?.id)
            CarToast.makeText(
                carContext,
                if (ok) "Grabando track" else "No se pudo iniciar: abre GeoPlano en el teléfono",
                CarToast.LENGTH_LONG
            ).show()
        }
        invalidate()
    }
}

/** Lista de planos: primero los que contienen tu ubicación. */
class MapListCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val loc = LocationRepository.location.value
        val maps = MapStore.maps.value.sortedWith(compareBy<MapEntry>(
            { m -> if (loc != null && m.contains(loc.latitude, loc.longitude)) 0 else 1 },
            { m -> if (loc == null) 0.0 else m.center()?.let { GeoMath.distance(loc.latitude, loc.longitude, it[0], it[1]) } ?: Double.MAX_VALUE }
        ))
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6)

        val list = ItemList.Builder().setNoItemsMessage("No hay planos importados")
        val selected = MapStore.selectedMapId.value
        for (m in maps.take(limit)) {
            val sub = when {
                !m.isGeoreferenced -> "Sin georreferencia"
                loc == null -> m.projection ?: "Georreferenciado"
                m.contains(loc.latitude, loc.longitude) -> "● Estás aquí"
                else -> m.center()?.let { "a " + GeoMath.formatDistance(GeoMath.distance(loc.latitude, loc.longitude, it[0], it[1])) } ?: ""
            }
            list.addItem(
                Row.Builder()
                    .setTitle(if (m.id == selected) "✓ ${m.name}" else m.name)
                    .addText(sub)
                    .setOnClickListener {
                        MapStore.select(m.id)
                        screenManager.pop()
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Planos")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
