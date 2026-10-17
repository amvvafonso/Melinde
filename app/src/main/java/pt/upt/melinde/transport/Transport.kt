package pt.upt.melinde.transport



interface Transport {
    val transportId: TransportMode;
    val melindeUuid: String;

    // States var1
    var searching: Boolean;


    suspend fun StartDiscovery() : Any;
    suspend fun StopDiscovery();

    suspend fun Send(device: Device, message: Message)

}

enum class TransportMode {
    BluetoothLE, WifiDirect
}
