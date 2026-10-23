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
import pt.upt.melinde.transport.Device
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Events emitted by the [GattServer] to the layer above.
 */
sealed interface GattServerEvent {
    /** A client opened a connection (not yet subscribed nor identified) */
    class Connected(val device: BluetoothDevice) : GattServerEvent

    /** A client disconnected */
    class Disconnected(val device: BluetoothDevice) : GattServerEvent

    /** A client sent its handshake: from now on it can be reached by [nodeId] */
    class PeerReady(val nodeId: UUID, val device: BluetoothDevice) : GattServerEvent

    /** A client wrote a message (type byte already removed) */
    class MessageReceived(val device: BluetoothDevice, val data: ByteArray) : GattServerEvent
}

/**
 * GATT server side of a BLE link: exposes the Melinde service, receives writes from clients
 * and sends notifications to subscribed clients.
 *
 * A client becomes reachable after two steps, in this order:
 * 1. it subscribes by writing the CCCD (onDescriptorWriteRequest) -> added to [subscribers]
 * 2. it sends the handshake with its node id (onCharacteristicWriteRequest) -> added to [nodeToDevice]
 *
 * @param context Application context
 *
 * @see GattClient
 */
@SuppressLint("MissingPermission")
class GattServer(private val context: Context) {

    /** A notification waiting to be sent */
    private class PendingNotification(
        val device: BluetoothDevice,
        val payload: ByteArray
    )

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _events = MutableSharedFlow<GattServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GattServerEvent> = _events.asSharedFlow()

    // ---------------------------------------------------------------------------------------
    // Connected clients
    // ---------------------------------------------------------------------------------------

    /** Clients that enabled notifications, i.e. the server is allowed to send data to them */
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    /** Node id (from the handshake) -> device of the active connection */
    private val nodeToDevice = ConcurrentHashMap<UUID, BluetoothDevice>()

    /** Address of the active connection -> node id (from the handshake) */
    private val addressToNode = ConcurrentHashMap<String, UUID>()

    /** Negotiated MTU per client address */
    private val deviceMtu = ConcurrentHashMap<String, Int>()

    // ---------------------------------------------------------------------------------------
    // Notification queue. Everything that reads or changes these fields must hold the lock.
    // One queue for the whole server: many phones only allow one pending notification at a time.
    // ---------------------------------------------------------------------------------------
    private val lock = Any()
    private val queue = ArrayDeque<PendingNotification>()
    private var busy = false

    /** Device of the notification currently being sent, used to unblock the queue on disconnect */
    private var inFlight: BluetoothDevice? = null

    // =======================================================================================
    // Public API
    // =======================================================================================

    /**
     * Opens the GATT server and adds the Melinde service.
     * [ready] becomes true when the service is added.
     */
    fun start() {
        if (server != null) return

        val gattServer = manager.openGattServer(context, callback)
        if (gattServer == null) {
            Log.e(BleShared.GATT_SERVER_TAG, "Could not open the GATT server")
            return
        }
        server = gattServer

        // Characteristic: the place where bytes are exchanged. Clients write on it and
        // the server notifies through it.
        val characteristic = BluetoothGattCharacteristic(
            BleShared.MESSAGE_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // CCCD: each client writes here to say whether it wants notifications
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                BleShared.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        messageCharacteristic = characteristic

        val service = BluetoothGattService(BleShared.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        gattServer.addService(service)
    }

    /**
     * Closes the GATT server and resets all state.
     */
    fun stop() {
        synchronized(lock) {
            queue.clear()
            busy = false
            inFlight = null
        }
        server?.close()
        server = null
        messageCharacteristic = null
        subscribers.clear()
        nodeToDevice.clear()
        addressToNode.clear()
        deviceMtu.clear()
        _ready.value = false
    }

    /**
     * Queues a notification to a connected node.
     *
     * @param device Node to send to (looked up by its node id, never by address)
     * @param data Message content (without the type byte)
     * @return true if the message was queued, false if the node is not reachable or the message is too big
     */
    fun send(device: Device, data: ByteArray): Boolean {
        val btDevice = nodeToDevice[device.deviceId]
        if (btDevice == null) {
            Log.w(BleShared.GATT_SERVER_TAG, "${device.deviceId} has no active connection")
            return false
        }
        if (btDevice !in subscribers) {
            Log.w(BleShared.GATT_SERVER_TAG, "${device.deviceId} is not subscribed")
            return false
        }

        val payload = byteArrayOf(BleShared.TYPE_DATA) + data
        val max = (deviceMtu[btDevice.address] ?: DEFAULT_MTU) - ATT_HEADER_BYTES
        if (payload.size > max) {
            Log.w(BleShared.GATT_SERVER_TAG, "Message too big: ${payload.size} bytes, max $max")
            return false
        }

        synchronized(lock) {
            queue.addLast(PendingNotification(btDevice, payload))
        }
        processNext()
        return true
    }

    // =======================================================================================
    // Queue
    // =======================================================================================

    /**
     * Sends the next queued notification if none is in progress.
     * Notifications refused by the stack are dropped and the next one is tried.
     */
    private fun processNext() {
        synchronized(lock) {
            while (!busy) {
                val gattServer = server
                val characteristic = messageCharacteristic
                if (gattServer == null || characteristic == null) {
                    queue.clear()
                    return
                }

                val next = queue.removeFirstOrNull() ?: return
                busy = true
                inFlight = next.device

                val result = gattServer.notifyCharacteristicChanged(
                    next.device, characteristic, false, next.payload
                )
                if (result != BluetoothStatusCodes.SUCCESS) {
                    Log.w(
                        BleShared.GATT_SERVER_TAG,
                        "Notification to ${next.device.address} refused: $result, dropped ${next.payload.size} bytes"
                    )
                    busy = false
                    inFlight = null
                }
            }
        }
    }

    /**
     * Removes everything related to a client that disconnected and unblocks the queue
     * if the notification in progress was for it.
     */
    private fun forget(device: BluetoothDevice) {
        subscribers.remove(device)
        deviceMtu.remove(device.address)
        // remove(key, value) only removes if the node still points to this connection
        addressToNode.remove(device.address)?.let { nodeToDevice.remove(it, device) }

        synchronized(lock) {
            queue.removeAll { it.device.address == device.address }
            if (inFlight?.address == device.address) {
                busy = false
                inFlight = null
            }
        }
        processNext()
    }

    // =======================================================================================
    // Callbacks
    // =======================================================================================

    private val callback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            _ready.value = ok
            if (ok) Log.i(BleShared.GATT_SERVER_TAG, "Melinde service added")
            else Log.e(BleShared.GATT_SERVER_TAG, "Could not add the service: $status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(BleShared.GATT_SERVER_TAG, "Connection ${device.address}: status=$status newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _events.tryEmit(GattServerEvent.Connected(device))
                    server?.setPreferredPhy(
                        device,
                        BluetoothDevice.PHY_LE_CODED_MASK,
                        BluetoothDevice.PHY_LE_CODED_MASK,
                        BluetoothDevice.PHY_OPTION_S8
                    )
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    forget(device)
                    _events.tryEmit(GattServerEvent.Disconnected(device))
                }
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
                        Log.i(BleShared.GATT_SERVER_TAG, "${device.address} subscribed")
                    }

                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        subscribers.remove(device)
                        status = BluetoothGatt.GATT_SUCCESS
                        Log.i(BleShared.GATT_SERVER_TAG, "${device.address} unsubscribed")
                    }

                    else -> Log.w(BleShared.GATT_SERVER_TAG, "Unexpected CCCD value: ${value.contentToString()}")
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

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            var valid = false

            if (characteristic.uuid == BleShared.MESSAGE_CHAR && !preparedWrite && value.isNotEmpty()) {
                valid = when (value[0]) {
                    BleShared.TYPE_HANDSHAKE -> handleHandshake(device, value)
                    BleShared.TYPE_DATA -> handleData(device, value)
                    else -> {
                        Log.w(BleShared.GATT_SERVER_TAG, "Unknown message type: ${value[0]}")
                        false
                    }
                }
            }

            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset, null
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                busy = false
                inFlight = null
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(BleShared.GATT_SERVER_TAG, "Notification to ${device.address} failed: $status")
            }
            processNext()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(BleShared.GATT_SERVER_TAG, "MTU with ${device.address}: $mtu")
            deviceMtu[device.address] = mtu
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            Log.d(BleShared.GATT_SERVER_TAG, "PHY ${device.address}: tx=$txPhy rx=$rxPhy status=$status")
        }
    }

    // =======================================================================================
    // Message handling
    // =======================================================================================

    /**
     * Handles [TYPE_HANDSHAKE][16 bytes of nodeId]: maps the node id to this connection.
     * @return true if the handshake is valid
     */
    private fun handleHandshake(device: BluetoothDevice, value: ByteArray): Boolean {
        if (value.size != 1 + BleShared.NODE_ID_BYTES) {
            Log.w(BleShared.GATT_SERVER_TAG, "Handshake with wrong size: ${value.size}")
            return false
        }

        val buffer = ByteBuffer.wrap(value, 1, BleShared.NODE_ID_BYTES)
        val nodeId = UUID(buffer.long, buffer.long)

        nodeToDevice[nodeId] = device
        addressToNode[device.address] = nodeId
        Log.i(BleShared.GATT_SERVER_TAG, "Handshake from $nodeId (${device.address})")
        _events.tryEmit(GattServerEvent.PeerReady(nodeId, device))
        return true
    }

    /**
     * Handles [TYPE_DATA][content]: only accepted after the handshake.
     * @return true if the message was accepted
     */
    private fun handleData(device: BluetoothDevice, value: ByteArray): Boolean {
        val nodeId = addressToNode[device.address]
        if (nodeId == null) {
            Log.w(BleShared.GATT_SERVER_TAG, "Data from ${device.address} before handshake")
            return false
        }

        val data = value.copyOfRange(1, value.size)
        Log.i(BleShared.GATT_SERVER_TAG, "Received ${data.size} bytes from $nodeId")
        _events.tryEmit(GattServerEvent.MessageReceived(device, data))
        return true
    }

    companion object {
        /** Default ATT MTU before any negotiation */
        private const val DEFAULT_MTU = 23

        /** Bytes of each packet used by the ATT protocol header (payload = MTU - 3) */
        private const val ATT_HEADER_BYTES = 3
    }
}