package com.example.beaconpoc

import android.app.Application
import com.example.sdk.Bearound

class BeaconPocApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val beaconSdk = Bearound(applicationContext)
        beaconSdk.initialize(R.drawable.ic_launcher_foreground)
    }
}
