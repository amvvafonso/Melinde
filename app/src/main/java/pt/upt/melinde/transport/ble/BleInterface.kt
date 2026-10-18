package pt.upt.melinde.transport.ble


import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import pt.upt.melinde.transport.Device
import pt.upt.melinde.transport.Message
import pt.upt.melinde.transport.Transport
import pt.upt.melinde.transport.TransportMode
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


/**
 * Interface to interact with Bluetooth LE
 *
 * @param context Context of application
 * @param nodeId
 * @author Afonso Viana
 */
class BleInterface(private val context: Context, private val nodeId: UUID) : Transport {

    /**
     * Devices on the proximity that support Melinde
     */
    private val availableDevices: ConcurrentHashMap<UUID, BluetoothDevice> = ConcurrentHashMap<UUID, BluetoothDevice>()
    private val gattClients: ConcurrentHashMap<UUID, GattClient> = ConcurrentHashMap<UUID, GattClient>()
    private val bleAdapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter


    override val transportId = TransportMode.BluetoothLE
    private val serviceUuid = ParcelUuid.fromString(BleShared.SERVICE_UUID)

    private val gattServer = GattServer(context)


    private val _advertising = MutableStateFlow(false)
    override val advertising: StateFlow<Boolean> = _advertising.asStateFlow()


    init {
        Log.d(BleShared.BLE_TAG, "CODED ${bleAdapter.isLeCodedPhySupported()} | ${bleAdapter.isLeExtendedAdvertisingSupported()} | dataLength ${bleAdapter.leMaximumAdvertisingDataLength} ||| FASTER ${bleAdapter.isLe2MPhySupported()}")
    }

    /**
     * Starts scanning the proximity for Devices that support Melinde
     *
     * @return Flow with devices found
     */
    @SuppressLint("MissingPermission")
    override fun startDiscovery() : Flow<Device> = callbackFlow {
        val scanner = bleAdapter.bluetoothLeScanner

        if (scanner == null) {
            close(
                IllegalStateException("Bluetooth desligado ou indisponível!")
            )
            return@callbackFlow
        }


        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = verifyScanResult(result)

                device?.let { trySend( it ) }
            }

            override fun onBatchScanResults(results: List<ScanResult?>?) {
                if (results == null) return

                results.forEach { r ->
                    val device = verifyScanResult(r)

                    device?.let { trySend( it ) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth desligado ou indisponível!"))
            }

        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(BleShared.SCAN_MODE)
            .build()

        scanner.startScan(listOf<ScanFilter>(scanFilter), settings, scanCallback)

        awaitClose {
            scanner.stopScan(scanCallback)
        }
    }



    /**
     * Converts ScanResult to Device Model and
     * adds the UUID to the device
     * @return Device?
     */
    private fun ScanResult.toDevice(): Device? {
        val bytes = scanRecord?.getManufacturerSpecificData(BleShared.MANUFACTURER_ID) ?: return null
        if (bytes.size != BleShared.NODE_ID_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        val id = UUID(buffer.getLong(), buffer.getLong())
        if (id == nodeId) return null
        return Device(id, null, transportId, rssi)
    }


     private val advertiseCallback = object : AdvertiseCallback() {

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _advertising.value = true
            Log.i(BleShared.BLE_TAG, "Advertising iniciado com sucesso")
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "The PDU is too large"

                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "Too many instances of BLE"

                ADVERTISE_FAILED_ALREADY_STARTED ->
                    "Callback already started"

                ADVERTISE_FAILED_INTERNAL_ERROR ->
                    "Internal error"

                ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "BLE advertisement is not supported"

                else -> "God have mercy on us all"
            }

            Log.e("ERRO", "Advertisement failed: code=$errorCode, message=$errorMessage")
    }
    }


    /**
     * Starts advertising Bluetooth LE to available devices on the proximity
     * and starts GATT server
     */
    @SuppressLint("MissingPermission")
    override fun startAdvertise() {
        if (_advertising.value) return

        val bleAdvertiser = bleAdapter.bluetoothLeAdvertiser

        //TODO: Implement maxDataLength
        val maxDataLength = bleAdapter.leMaximumAdvertisingDataLength

        if (!bleAdapter.isLe2MPhySupported){
            Log.e(BleShared.BLE_TAG, "LE2MPHY is not supported")
        }

        gattServer.start()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(BleShared.ADVERTISE_MODE)
            .setTxPowerLevel(BleShared.TX_POWER_MODE)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BleShared.MANUFACTURER_ID, nodeIdBytes())
            .build()

        bleAdvertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    /**
     * Stops advertising device on Bluetooth LE and GATT connections
     * @see startAdvertise
     */
    @SuppressLint("MissingPermission")
    override fun stopAdvertise() {
        bleAdapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        _advertising.value = false
        gattServer.stop()
    }

    /**
     * Allocates a buffer to store the UUID in Bytes
     *
     * @return ByteArray
     */
    private fun nodeIdBytes(): ByteArray =
        ByteBuffer.allocate(BleShared.NODE_ID_BYTES)
            .putLong(nodeId.mostSignificantBits)
            .putLong(nodeId.leastSignificantBits)
            .array()

    override suspend fun send(device: Device, message: Message) {
        //TODO: Not implemented yet
    }



    /**
     * Verifies ScanResult from Bluetooth LE Scans. Adds device to availableDevices
     *
     * @param result
     * @see BleInterface
     * @return Device?
     */
    private fun verifyScanResult(result: ScanResult?): Device? {
        if (result == null) return null

        val device = result.toDevice() ?: return null

        availableDevices[device.deviceId] = result.device

        gattClients.computeIfAbsent(device.deviceId) {
            Log.d(
                BleShared.BLE_TAG,
                "Creating GattClient for ${result.device.address}, " +
                        "deviceId=${device.deviceId}"
            )
            GattClient(context, result.device)
        }

        return device
    }



}