# CHANGES — Migration to Service Data (UUID 0xBEAD)

## IBeaconParser.kt

### New: `BEAD_SERVICE_UUID`
A `ParcelUuid` constant for the 16-bit `0xBEAD` UUID expressed in 128-bit form (`0000BEAD-0000-1000-8000-00805F9B34FB`).

### New data class: `BeadServiceData`
```kotlin
data class BeadServiceData(
    val major: Int,
    val minor: Int,
    val metadata: BeaconMetadata,
    val rssi: Int
)
```

### New method: `parseServiceData(scanRecord, rssi)`
Parses the 11-byte (little-endian) Service Data payload under UUID `0xBEAD` via `ScanRecord.getServiceData()`:

| Offset | Bytes | Field       | Type      |
|--------|-------|-------------|-----------|
| 0-1    | 2     | Firmware    | uint16 LE |
| 2-3    | 2     | Major       | uint16 LE |
| 4-5    | 2     | Minor       | uint16 LE |
| 6-7    | 2     | Motion      | uint16 LE |
| 8      | 1     | Temperature | int8      |
| 9-10   | 2     | Battery mV  | uint16 LE |

Returns `BeadServiceData` with a populated `BeaconMetadata`, or `null` if the data is missing or invalid.

---

## BeaconManager.kt

### Updated: `startRanging()`
Added a second `ScanFilter` for BEAD Service Data:
```kotlin
ScanFilter.Builder()
    .setServiceData(IBeaconParser.BEAD_SERVICE_UUID, byteArrayOf(), byteArrayOf())
    .build()
```
The filters are combined with OR semantics — a scan result that matches either path (iBeacon OR BEAD Service Data) is delivered.

### Rewritten: `processScanResult()`
New priority logic:
1. **BEAD Service Data** → major, minor AND full metadata
2. **iBeacon manufacturer data** → major, minor without metadata (fallback)

---

## BluetoothManager.kt

### Rewritten: `processScanResult()`
Same priority logic as BeaconManager:
1. **BEAD Service Data** → major, minor AND full metadata
2. **iBeacon manufacturer data** → major, minor without metadata (fallback)

### Removed: `parseBeaconMetadata()`
Parsing of the name-based `"B:firmware_?_battery_movements_temperature"` payload was removed.

### Simplified deduplication
`shouldProcessBeacon()` now keys on `"major.minor"` instead of `"uuid-major-minor"`.

---

## Field changes
- **battery**: now reported in millivolts (e.g., `3269`) instead of a 0-100 percentage
- **firmware**: now reported as an integer-as-string (e.g., `"1"`) instead of a semantic version (e.g., `"2.1.0"`)

## Backward compatibility
Beacons running the old name-based firmware **will no longer be detected** — intentional.
