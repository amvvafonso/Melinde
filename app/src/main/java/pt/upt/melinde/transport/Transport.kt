package pt.upt.melinde.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow


interface Transport {
    val transportId: TransportMode;
    val advertising: StateFlow<Boolean>


    fun startDiscovery() : Flow<Device>;

    fun startAdvertise();
    fun stopAdvertise();


    suspend fun send(device: Device, message: Message)

}

enum class TransportMode {
    BluetoothLE, WifiDirect
}
