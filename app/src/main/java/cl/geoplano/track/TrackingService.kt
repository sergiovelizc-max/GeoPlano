package cl.geoplano.track

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cl.geoplano.R
import cl.geoplano.data.MapStore
import cl.geoplano.data.Track
import cl.geoplano.data.TrackPoint
import cl.geoplano.geo.GeoMath
import cl.geoplano.location.LocationRepository
import cl.geoplano.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Estado del track en grabación, compartido entre teléfono y Android Auto. */
object TrackRecorder {
    private val _active = MutableStateFlow<Track?>(null)
    val active: StateFlow<Track?> = _active

    val isRecording get() = _active.value != null

    fun begin(mapId: String?) {
        val name = "Track " + SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
        _active.value = Track(UUID.randomUUID().toString(), mapId, name, emptyList())
    }

    fun add(loc: Location) {
        val t = _active.value ?: return
        if (loc.hasAccuracy() && loc.accuracy > 30f) return
        val last = t.points.lastOrNull()
        if (last != null && GeoMath.distance(last.lat, last.lon, loc.latitude, loc.longitude) < 3.0) return
        val p = TrackPoint(loc.latitude, loc.longitude, loc.time, if (loc.hasAltitude()) loc.altitude else null)
        _active.value = t.copy(points = t.points + p)
        // guardado incremental cada 20 puntos para no perder datos
        if ((t.points.size + 1) % 20 == 0) MapStore.saveTrack(_active.value!!)
    }

    fun finish(): Track? {
        val t = _active.value ?: return null
        _active.value = null
        if (t.points.size >= 2) MapStore.saveTrack(t) else MapStore.deleteTrack(t.id)
        return t
    }
}

/** Servicio en primer plano: sigue grabando con la pantalla apagada o desde Android Auto. */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TrackRecorder.finish()
            stopSelf()
            return START_NOT_STICKY
        }
        MapStore.init(this)
        if (!TrackRecorder.isRecording) TrackRecorder.begin(intent?.getStringExtra(EXTRA_MAP_ID))
        val notif = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        if (job == null) {
            LocationRepository.acquire(this)
            job = scope.launch {
                LocationRepository.location.filterNotNull().collect { TrackRecorder.add(it) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        if (job != null) LocationRepository.release()
        job = null
        if (TrackRecorder.isRecording) TrackRecorder.finish()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(getString(R.string.tracking_running))
            .setContentText("GeoPlano registra tu recorrido")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Detener", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "cl.geoplano.STOP_TRACK"
        private const val EXTRA_MAP_ID = "mapId"

        /** @return false si el sistema no permitió iniciar el servicio. */
        fun start(context: Context, mapId: String?): Boolean {
            val i = Intent(context, TrackingService::class.java).putExtra(EXTRA_MAP_ID, mapId)
            return try {
                ContextCompat.startForegroundService(context, i)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            TrackRecorder.finish()
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
