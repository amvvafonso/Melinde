package pt.upt.melinde.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface GattServerEvent {
    class Connected(val device: BluetoothDevice) : GattServerEvent
    class Disconnected(val device: BluetoothDevice) : GattServerEvent
    class MessageReceived(val device: BluetoothDevice, val data: ByteArray) : GattServerEvent
}

/**
 * Class that allows the user to interact with GATT server
 * @param context
 */
@SuppressLint("MissingPermission")
class GattServer(private val context: Context)  {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * Clients that are subscribed and asked to receive notifications. In Kotlin notifications is
     * when a Client sends data without a request
     */
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _events = MutableSharedFlow<GattServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GattServerEvent> = _events.asSharedFlow()

    /**
     * Starts GATT server with permissions and characteristics
     */
    fun start() {
        if (server != null) return

        val gattServer = manager.openGattServer(context, callback)
        if (gattServer == null) {
            Log.e(BleShared.GATT_TAG, "An error occurred when trying to start GATT server")
            return
        }
        server = gattServer

        // Characteristic represents a place in the GATT server where bytes are exchanged.
        // It determines what can be done to the bytes
        val characteristic = BluetoothGattCharacteristic(
            BleShared.MESSAGE_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        // Descriptor represents whether the client wants to receive notifications or not
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                BleShared.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        messageCharacteristic = characteristic

        val service = BluetoothGattService(
            BleShared.SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(characteristic)
        gattServer.addService(service)
    }

    /**
     * Stops the GATT server and resets all properties
     */
    fun stop() {
        server?.close()
        server = null
        messageCharacteristic = null
        subscribers.clear()
        _ready.value = false
    }

    /**
     * Sends data to a subscriber
     *
     * @param device BluetoothDevice
     * @param data ByteArray
     * @return Boolean Whether the data was accepted or rejected
     */
    fun notify(device: BluetoothDevice, data: ByteArray): Boolean {
        val gattServer = server ?: return false
        val characteristic = messageCharacteristic ?: return false
        if (device !in subscribers) return false
        val result = gattServer.notifyCharacteristicChanged(device, characteristic, false, data)
        return result == BluetoothStatusCodes.SUCCESS
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            _ready.value = ok
            if (ok) Log.i(BleShared.GATT_TAG, "GATT was added successfully")
            else Log.e(BleShared.GATT_TAG, "An error occurred while adding the GATT Service: $status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(BleShared.GATT_TAG, "Connected to ${device.address}: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    _events.tryEmit(GattServerEvent.Connected(device))
                BluetoothProfile.STATE_DISCONNECTED -> {
                    subscribers.remove(device)
                    _events.tryEmit(GattServerEvent.Disconnected(device))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val valid = characteristic.uuid == BleShared.MESSAGE_CHAR && !preparedWrite
            if (valid) {
                Log.i(BleShared.GATT_TAG, "Received ${value.size} bytes from Device:${device.address}")
                _events.tryEmit(GattServerEvent.MessageReceived(device, value))
            }
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset, value
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            var status = BluetoothGatt.GATT_FAILURE
            if (descriptor.uuid == BleShared.CCCD && !preparedWrite) {
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        subscribers.add(device)
                        status = BluetoothGatt.GATT_SUCCESS
                        Log.i(BleShared.GATT_TAG, "${device.address} has subscribed")
                    }
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        subscribers.remove(device)
                        status = BluetoothGatt.GATT_SUCCESS
                    }
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, status, offset, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val value = if (device in subscribers)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Log.d(BleShared.GATT_TAG, "Notification was sent to ${device.address}: status=$status")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(BleShared.GATT_TAG, "MTU with ${device.address}: $mtu")
        }
    }
}