package pt.upt.melinde.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Author: Afonso Viana
 * Created: 10/18/26
 */

/**
 * Class that allows the user to interact with GATT Client, find services and writes to GATT server
 *
 * @param context
 * @param device Bluetooth Device Client
 *
 * @see GattServer
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, private val device: BluetoothDevice) {

    private var bluetoothGatt: BluetoothGatt? = null;

    private val _connected = MutableStateFlow(false)
    val connected : StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Connects to the other client via GATT
     */
    fun connect() {
        if (bluetoothGatt != null) return

        bluetoothGatt = device.connectGatt(
            context,
            false,
            bluetoothGattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_CODED_MASK
        )
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(BleShared.GATT_CLIENT_TAG, "Status $status, newState $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connected.value = true

                    if (status == BluetoothGatt.GATT_SUCCESS){
                        gatt?.discoverServices()
                    }

                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connected.value = false
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            value.forEach { r -> Log.d(BleShared.GATT_CLIENT_TAG, r.toString()) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            value.forEach { r -> Log.d(BleShared.GATT_CLIENT_TAG, r.toString()) }
        }


        override fun onPhyUpdate(gatt: BluetoothGatt?, txPhy: Int, rxPhy: Int, status: Int) {
            Log.d(BleShared.GATT_CLIENT_TAG, "THE PHY WAS UPDATED ON DEVICE ${device.address} TRX: $txPhy, RX: $rxPhy: Success ${status == BluetoothGatt.GATT_SUCCESS}")

        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {

        }
    }

    /**
     * Closes the GATT connnection
     * @see connect
     */
    fun close(){
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connected.value = false

    }
}