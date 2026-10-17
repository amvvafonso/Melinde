package pt.upt.melinde.transport

data class Message(
    val messageId : String,
    val senderId: String,
    val destinationId: String,
    val payload: ByteArray
) {
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
