package io.bearound.sdk

/**
 * Interface for receiving callbacks about region entry and exit events.
 */
interface RegionListener {
    /**
     * Called when the device enters a beacon region.
     *
     * @param regionName The name of the region that was entered
     */
    fun onRegionEnter(regionName: String)

    /**
     * Called when the device exits a beacon region.
     *
     * @param regionName The name of the region that was exited
     */
    fun onRegionExit(regionName: String)
}
