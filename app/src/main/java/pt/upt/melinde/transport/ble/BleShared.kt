package pt.upt.melinde.transport.ble

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings
import java.util.UUID

object BleShared {
    // ----------------------------------------------------
    // Bluetooth LE properties
    // ----------------------------------------------------
    const val BLE_TAG = "BleInterface"
    const val GATT_SERVER_TAG = "GattServer"
    const val GATT_CLIENT_TAG = "GattClient"
    const val MANUFACTURER_ID = 0xFFFF
    const val SERVICE_UUID = "f69be1c6-7319-f3a0-cfbf-8a3868346ce3"
    const val MESSAGE_BYTES = 517;
    const val NODE_ID_BYTES = 16
    const val MESSAGE_CHAR_UUID = "7c1d4e52-9b0a-4f3e-8a6d-2e5b1c9f0a47"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val TYPE_HANDSHAKE: Byte = 0x01
    const val TYPE_DATA: Byte = 0x02

    // ----------------------------------------------------
    // Bluetooth LE configuration
    // ----------------------------------------------------
    const val TX_POWER_MODE = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    const  val SCAN_MODE = ScanSettings.SCAN_MODE_LOW_POWER
    const val ADVERTISE_MODE = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY



    // ----------------------------------------------------
    // GattServer properties
    // ----------------------------------------------------
    val SERVICE: UUID = UUID.fromString(SERVICE_UUID)
    val MESSAGE_CHAR: UUID = UUID.fromString(MESSAGE_CHAR_UUID)
    val CCCD: UUID = UUID.fromString(CCCD_UUID)
}