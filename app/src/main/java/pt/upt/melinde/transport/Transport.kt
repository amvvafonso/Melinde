package pt.upt.melinde.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow


interface Transport {
    /**
     * Identifies the transport mode being used
     *
     * @see TransportMode
     */
    val transportId: TransportMode

    /**
     * Shows current connection state
     *
     * @see TransportMode
     */

    var transportState: TransportState


    /**
     * The messages received
     */
    val messages: Flow<ByteArray>


    /**
     * Method to send messages between devices
     *
     * @see Device
     * @see Message
     */
    fun send(device: Device, message: Message)
    suspend fun processMessage(byteArray: ByteArray)

}

enum class TransportMode {
    BluetoothLE, WifiDirect
}
