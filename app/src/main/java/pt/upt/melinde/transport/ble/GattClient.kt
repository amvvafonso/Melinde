package pt.upt.melinde.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import java.nio.ByteBuffer
import java.util.UUID

/*
 * Author: Afonso Viana
 * Created: 10/18/26
 */

/**
 * GATT client side of a BLE link: connects to another node's GATT server, subscribes to
 * its notifications, identifies itself with a handshake and writes messages to it.
 *
 * Connection flow (each step only starts on the callback of the previous one, because
 * Android only runs one GATT operation at a time per connection):
 * connect -> discoverServices -> requestMtu -> subscribe (CCCD) -> handshake -> messages
 *
 * @param context Application context
 * @param device Remote device found by the scan
 * @param nodeId This node's id, sent to the server in the handshake
 *
 * @see GattServer
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val nodeId: UUID
) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _mtu = MutableStateFlow(DEFAULT_MTU)
    val mtu: StateFlow<Int> = _mtu.asStateFlow()

    /** Messages received from the server through notifications (type byte already removed) */
    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    // ---------------------------------------------------------------------------------------
    // Write queue. Everything that reads or changes these three fields must hold the lock.
    // ---------------------------------------------------------------------------------------
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var busy = false
    private var handshakeDone = false

    // =======================================================================================
    // Public API
    // =======================================================================================

    /**
     * Starts the GATT connection. Does nothing if a connection already exists.
     */
    fun connect() {
        if (bluetoothGatt != null) return

        bluetoothGatt = device.connectGatt(
            context,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_CODED_MASK
        )
    }

    /**
     * Queues a message to be written on the server's characteristic.
     * Messages only leave after the handshake, one at a time.
     *
     * @param data Message content (without the type byte)
     * @return false if the message does not fit in a single write, true if it was queued
     */
    fun send(data: ByteArray): Boolean {
        val payload = byteArrayOf(BleShared.TYPE_DATA) + data
        val max = mtu.value - ATT_HEADER_BYTES
        if (payload.size > max) {
            Log.w(BleShared.GATT_CLIENT_TAG, "Message too big: ${payload.size} bytes, max $max")
            return false
        }

        synchronized(lock) {
            queue.addLast(payload)
        }
        processNext()
        return true
    }

    /**
     * Closes the GATT connection and resets all state, so that [connect] can start again.
     */
    fun close() {
        synchronized(lock) {
            queue.clear()
            busy = false
            handshakeDone = false
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
        messageCharacteristic = null
        _connected.value = false
        _mtu.value = DEFAULT_MTU
    }

    // =======================================================================================
    // Connection steps
    // =======================================================================================

    /**
     * Enables notifications locally and writes the CCCD so the server knows we subscribed.
     * The result arrives in onDescriptorWrite.
     */
    private fun subscribe(gatt: BluetoothGatt) {
        val characteristic = messageCharacteristic ?: return
        val descriptor = characteristic.getDescriptor(BleShared.CCCD)

        if (descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(BleShared.GATT_CLIENT_TAG, "Could not enable notifications")
            gatt.disconnect()
            return
        }

        val result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(BleShared.GATT_CLIENT_TAG, "CCCD write refused: $result")
            gatt.disconnect()
        }
    }

    /**
     * Sends [TYPE_HANDSHAKE][16 bytes of nodeId] so the server can map this connection to our id.
     * The result arrives in onCharacteristicWrite.
     */
    private fun sendHandshake(gatt: BluetoothGatt) {
        val payload = ByteBuffer.allocate(1 + BleShared.NODE_ID_BYTES)
            .put(BleShared.TYPE_HANDSHAKE)
            .putLong(nodeId.mostSignificantBits)
            .putLong(nodeId.leastSignificantBits)
            .array()

        val accepted = synchronized(lock) {
            busy = true
            val ok = write(payload)
            if (!ok) busy = false
            ok
        }

        if (!accepted) {
            Log.e(BleShared.GATT_CLIENT_TAG, "Handshake refused, disconnecting")
            gatt.disconnect()
        }
    }

    // =======================================================================================
    // Queue
    // =======================================================================================

    /**
     * Sends the next queued message if the handshake is done and no write is in progress.
     * Messages refused by the stack are dropped and the next one is tried.
     */
    private fun processNext() {
        synchronized(lock) {
            while (handshakeDone && !busy) {
                val payload = queue.removeFirstOrNull() ?: return
                busy = true
                if (!write(payload)) {
                    busy = false
                    Log.w(BleShared.GATT_CLIENT_TAG, "Dropped message of ${payload.size} bytes")
                }
            }
        }
    }

    /**
     * Asks the Android stack to write [payload] on the characteristic.
     * Must be called with the lock held.
     *
     * @return true if the stack accepted the request (the result comes in onCharacteristicWrite)
     */
    private fun write(payload: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = messageCharacteristic ?: return false

        val result = gatt.writeCharacteristic(
            characteristic,
            payload,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.w(BleShared.GATT_CLIENT_TAG, "Write refused by the stack: $result")
        }
        return result == BluetoothStatusCodes.SUCCESS
    }

    // =======================================================================================
    // Callbacks
    // =======================================================================================

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(BleShared.GATT_CLIENT_TAG, "Connection ${device.address}: status=$status newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(BleShared.GATT_CLIENT_TAG, "Service discovery failed: $status")
                gatt.disconnect()
                return
            }

            messageCharacteristic = gatt
                .getService(UUID.fromString(BleShared.SERVICE_UUID))
                ?.getCharacteristic(BleShared.MESSAGE_CHAR)

            if (messageCharacteristic == null) {
                Log.w(BleShared.GATT_CLIENT_TAG, "This device does not support the Melinde network")
                gatt.disconnect()
                return
            }

            // If the MTU request can't even start, subscribe with the default MTU
            if (!gatt.requestMtu(BleShared.MESSAGE_BYTES)) {
                Log.w(BleShared.GATT_CLIENT_TAG, "MTU request refused, using default MTU")
                subscribe(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(BleShared.GATT_CLIENT_TAG, "MTU set to $mtu")
                _mtu.value = mtu
            } else {
                Log.w(BleShared.GATT_CLIENT_TAG, "MTU request failed: $status, using default MTU")
            }
            // The subscription happens whether or not the MTU changed
            subscribe(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleShared.CCCD) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(BleShared.GATT_CLIENT_TAG, "Subscribed to notifications")
                sendHandshake(gatt)
            } else {
                Log.e(BleShared.GATT_CLIENT_TAG, "Subscription failed: $status")
                gatt.disconnect()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val handshakeFailed = synchronized(lock) {
                val wasHandshake = !handshakeDone
                if (wasHandshake && status == BluetoothGatt.GATT_SUCCESS) handshakeDone = true
                busy = false
                wasHandshake && status != BluetoothGatt.GATT_SUCCESS
            }

            if (handshakeFailed) {
                Log.e(BleShared.GATT_CLIENT_TAG, "Handshake rejected by the server: $status")
                gatt.disconnect()
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(BleShared.GATT_CLIENT_TAG, "Write confirmed")
            } else {
                Log.w(BleShared.GATT_CLIENT_TAG, "Write failed: $status")
            }
            processNext()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (value.isEmpty() || value[0] != BleShared.TYPE_DATA) {
                Log.w(BleShared.GATT_CLIENT_TAG, "Ignored notification with ${value.size} bytes")
                return
            }
            val data = value.copyOfRange(1, value.size)
            Log.i(BleShared.GATT_CLIENT_TAG, "Received ${data.size} bytes from server")
            _messages.tryEmit(data)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.d(BleShared.GATT_CLIENT_TAG, "PHY ${device.address}: tx=$txPhy rx=$rxPhy status=$status")
        }
    }

    companion object {
        /** Default ATT MTU before any negotiation */
        private const val DEFAULT_MTU = 23

        /** Bytes of each write used by the ATT protocol header (payload = MTU - 3) */
        private const val ATT_HEADER_BYTES = 3
    }
}