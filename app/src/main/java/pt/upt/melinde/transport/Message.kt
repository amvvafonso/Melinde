package pt.upt.melinde.transport

import kotlinx.serialization.Serializable
import pt.upt.melinde.transport.ble.BleShared
import java.nio.ByteBuffer
import java.util.UUID



data class Message(val messageId : String, val senderId: UUID, val destinationId: UUID, val payload: ByteArray) {





    fun toByteArray() : ByteArray{
        return ByteBuffer.allocate(BleShared.NODE_ID_BYTES)
            .putLong(destinationId.mostSignificantBits)
            .putLong(destinationId.leastSignificantBits)
            .putLong(senderId.mostSignificantBits)
            .putLong(senderId.leastSignificantBits)
            .put(payload)
            .array()
    }




    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Message

        if (messageId != other.messageId) return false
        if (senderId != other.senderId) return false
        if (destinationId != other.destinationId) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + destinationId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
