package com.drivemap.app

import android.app.Application
import com.drivemap.app.core.Core
import org.maplibre.android.MapLibre

class DriveMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        Core.init(this)
    }
}
