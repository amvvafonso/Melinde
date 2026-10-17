package pt.upt.melinde.transport

data class Device(
    val deviceId: String,
    val name: String?,
    val transportId: String,
    val rssi: Int? = null
)