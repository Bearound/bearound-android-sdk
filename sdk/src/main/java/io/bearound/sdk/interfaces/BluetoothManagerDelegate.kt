package io.bearound.sdk.interfaces

import io.bearound.sdk.models.BeaconMetadata
import java.util.UUID

/**
 * Delegate interface for Bluetooth manager events
 */
interface BluetoothManagerDelegate {
    /**
     * Called when a beacon is discovered via BLE scanning
     */
    fun didDiscoverBeacon(
        uuid: UUID,
        major: Int,
        minor: Int,
        rssi: Int,
        txPower: Int,
        metadata: BeaconMetadata?,
        isConnectable: Boolean
    )

    /**
     * Called when Bluetooth state changes
     */
    fun didUpdateBluetoothState(isPoweredOn: Boolean)
}

