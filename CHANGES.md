# CHANGES — Migração para Service Data (UUID 0xBEAD)

## IBeaconParser.kt

### Novo: `BEAD_SERVICE_UUID`
Constante `ParcelUuid` para o UUID 16-bit `0xBEAD` em formato 128-bit (`0000BEAD-0000-1000-8000-00805F9B34FB`).

### Nova data class: `BeadServiceData`
```kotlin
data class BeadServiceData(
    val major: Int,
    val minor: Int,
    val metadata: BeaconMetadata,
    val rssi: Int
)
```

### Novo método: `parseServiceData(scanRecord, rssi)`
Parseia o payload binário de 11 bytes (Little-Endian) do Service Data com UUID `0xBEAD` via `ScanRecord.getServiceData()`:

| Offset | Bytes | Campo       | Tipo      |
|--------|-------|-------------|-----------|
| 0-1    | 2     | Firmware    | uint16 LE |
| 2-3    | 2     | Major       | uint16 LE |
| 4-5    | 2     | Minor       | uint16 LE |
| 6-7    | 2     | Motion      | uint16 LE |
| 8      | 1     | Temperature | int8      |
| 9-10   | 2     | Battery mV  | uint16 LE |

Retorna `BeadServiceData` com `BeaconMetadata` preenchido, ou `null` se não presente/inválido.

---

## BeaconManager.kt

### Atualizado: `startRanging()`
Adicionado segundo `ScanFilter` para BEAD Service Data:
```kotlin
ScanFilter.Builder()
    .setServiceData(IBeaconParser.BEAD_SERVICE_UUID, byteArrayOf(), byteArrayOf())
    .build()
```
Os filtros funcionam como OR — um scan result que case com qualquer um dos dois (iBeacon OU BEAD Service Data) será entregue.

### Reescrito: `processScanResult()`
Nova lógica de prioridade:
1. **BEAD Service Data** → major, minor E metadata completa
2. **iBeacon manufacturer data** → major, minor sem metadata (fallback)

---

## BluetoothManager.kt

### Reescrito: `processScanResult()`
Mesma lógica de prioridade do BeaconManager:
1. **BEAD Service Data** → major, minor E metadata completa
2. **iBeacon manufacturer data** → major, minor sem metadata (fallback)

### Removido: `parseBeaconMetadata()`
Parsing do nome `"B:firmware_?_battery_movements_temperature"` foi removido.

### Simplificada deduplicação
`shouldProcessBeacon()` agora usa chave `"major.minor"` em vez de `"uuid-major-minor"`.

---

## Mudanças nos campos
- **battery**: agora recebe millivolts (ex: 3269) em vez de porcentagem (0-100)
- **firmware**: agora recebe integer como string (ex: "1") em vez de versão semântica (ex: "2.1.0")

## Backward compatibility
Beacons com firmware antigo (Name-based) **não serão mais detectados** — intencional.
