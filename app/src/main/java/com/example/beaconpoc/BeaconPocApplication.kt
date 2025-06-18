package com.example.beaconpoc

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.sdk.BeAround

class BeaconPocApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val beAround = BeAround(applicationContext)


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beAround.initialize(
                iconNotification = R.drawable.ic_launcher_foreground,
                debug = true
            )
        }
    }
}
