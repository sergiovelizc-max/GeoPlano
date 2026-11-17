package cl.geoplano.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fuente única de ubicación GPS para el teléfono, Android Auto y la grabación de tracks.
 * Usa LocationManager directamente (sin Google Play Services) para funcionar sin señal de datos.
 * Los consumidores se registran con acquire()/release().
 */
object LocationRepository {

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private var users = 0
    private var manager: LocationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val prev = _location.value
            // preferir GPS: ignorar una ubicación de red peor si hay un GPS reciente
            if (prev != null && loc.provider == LocationManager.NETWORK_PROVIDER &&
                prev.provider == LocationManager.GPS_PROVIDER &&
                loc.time - prev.time < 10_000 && loc.accuracy > prev.accuracy
            ) return
            _location.value = loc
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun acquire(context: Context) {
        users++
        startIfPossible(context)
    }

    @SuppressLint("MissingPermission")
    private fun startIfPossible(context: Context) {
        if (manager != null || users == 0 || !hasPermission(context)) return
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        val providers = lm.getProviders(true)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in providers) {
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { last ->
                    val cur = _location.value
                    if (cur == null || last.time > cur.time) _location.value = last
                }
                runCatching { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) }
            }
        }
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
        if (users == 0) {
            manager?.removeUpdates(listener)
            manager = null
        }
    }

    /** Re-intenta tras conceder el permiso o activar el GPS. */
    @Synchronized
    fun restart(context: Context) {
        manager?.removeUpdates(listener)
        manager = null
        startIfPossible(context)
    }
}
