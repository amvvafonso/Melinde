package pt.upt.melinde.transport

import java.util.UUID

data class Device(
    val deviceId: UUID,
    val name: String?,
    val transportId: TransportMode,
    val rssi: Int? = null
)