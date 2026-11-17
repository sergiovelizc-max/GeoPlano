package cl.geoplano

import android.app.Application
import cl.geoplano.data.MapStore

class GeoPlanoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapStore.init(this)
    }
}
