package com.example.beaconpoc

import android.app.Application
import com.example.sdk.BeAround

class BeaconPocApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val beAround = BeAround(applicationContext)
        beAround.initialize(
            iconNotification = R.drawable.ic_launcher_foreground,
            debug = true
        )
    }
}
