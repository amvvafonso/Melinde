package pt.upt.melinde.transport.ble

import android.content.Context
import java.util.UUID

object NodeIdStore {
    fun get(context: Context): UUID {
        val prefs = context.getSharedPreferences("melinde", Context.MODE_PRIVATE)
        prefs.getString("node_id", null)?.let { return UUID.fromString(it) }

        val id = UUID.randomUUID()
        prefs.edit().putString("node_id", id.toString()).apply()
        return id
    }
}