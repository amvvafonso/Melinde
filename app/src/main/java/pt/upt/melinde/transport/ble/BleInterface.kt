package pt.upt.melinde.transport.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcel
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.PackageManagerCompat.LOG_TAG
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import pt.upt.melinde.transport.Transport
import pt.upt.melinde.transport.TransportMode
import java.util.UUID




abstract class BleInterface(context: Context) : Transport {

    // Interface
    override val transportId = TransportMode.BluetoothLE
    override val melindeUuid: String = "6d656c69-6e64-4500-8000-000000000001";
    override var searching = false;

    // Interface dependent
    private val bleAdapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bleAdapter.bluetoothLeAdvertiser;

    // Consts
    private val SCAN_MODE = ScanSettings.SCAN_MODE_LOW_POWER;
    private val TX_POWER_MODE = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
    private val LATENCY_MODE = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;


    @SuppressLint("MissingPermission")
    override suspend fun StartDiscovery() = callbackFlow {
        val scanner = bleAdapter.bluetoothLeScanner

        if (scanner == null){
            close(
                IllegalStateException("Bluetooth desligado ou indisponível!")
            )
            return@callbackFlow;
        }

        val scanCallback = object : ScanCallback(){
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                searching = true;
                trySend(result)
            }

            override fun onBatchScanResults(results: List<ScanResult?>?) {
                results?.forEach { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                searching = false;
                close(
                    IllegalStateException("Bluetooth desligado ou indisponível!")
                )
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(SCAN_MODE)
            .build()

        scanner.startScan(null, settings, scanCallback)

        awaitClose {
            scanner.stopScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission", "RestrictedApi")
    override suspend fun StopDiscovery(){
        val maxDataLength = bleAdapter.leMaximumAdvertisingDataLength;
        if (bleAdapter.isLe2MPhySupported){
            Log.e(LOG_TAG, "LE2MPHY não é suportado")
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(LATENCY_MODE)
            .setTxPowerLevel(TX_POWER_MODE)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val advertiseCallback = object : AdvertiseCallback() {

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.e(LOG_TAG, "Advertising iniciado com sucesso")
            }

            override fun onStartFailure(errorCode: Int) {
                val errorMessage = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE ->
                        "Os dados do anúncio são demasiado grandes"

                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                        "Demasiados anunciantes BLE ativos"

                    ADVERTISE_FAILED_ALREADY_STARTED ->
                        "Este callback já está a anunciar"

                    ADVERTISE_FAILED_INTERNAL_ERROR ->
                        "Erro interno do Bluetooth"

                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                        "Advertising BLE não suportado"

                    else -> "Erro desconhecido"
                }

                Log.e(LOG_TAG, "Advertising falhou: código=$errorCode, motivo=$errorMessage")
            }
        }

        bleAdvertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
    }



}