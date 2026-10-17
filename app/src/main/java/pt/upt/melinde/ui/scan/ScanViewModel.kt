package pt.upt.melinde.ui.scan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.upt.melinde.transport.Device
import pt.upt.melinde.transport.ble.BleInterface
import pt.upt.melinde.transport.ble.NodeIdStore
import java.util.UUID

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val bleInterface = BleInterface(app, NodeIdStore.get(app))

    private val found = MutableStateFlow<Map<UUID, Device>>(emptyMap())
    val devices: StateFlow<List<Device>> =
        found.map { it.values.sortedByDescending { d -> d.rssi } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    val advertising: StateFlow<Boolean> = bleInterface.advertising

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    fun startScan() {
        if (job?.isActive == true) return
        _error.value = null
        _scanning.value = true
        job = viewModelScope.launch {
            try {
                bleInterface.startDiscovery().collect { device ->
                    found.update { it + (device.deviceId to device) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                _error.value = e.message
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        job?.cancel()
    }

    fun startAdvertising() = bleInterface.startAdvertise()

    fun stopAdvertising() = bleInterface.stopAdvertise()

    override fun onCleared() {
        bleInterface.stopAdvertise()

    }

}