package pt.upt.melinde.ui.scan

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.upt.melinde.transport.ble.BleInterface

data class FoundDevice(val address: String, val name: String?, val rssi: Int, val uuid: String)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = BleInterface(app)

    private val found = MutableStateFlow<Map<String, FoundDevice>>(emptyMap())
    val devices: StateFlow<List<FoundDevice>> =
        found.map { it.values.sortedByDescending { d -> d.rssi } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _scanning = MutableStateFlow(false)
    private val _advertising = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    val advirtising: StateFlow<Boolean> = _advertising

    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (job?.isActive == true) return
        _scanning.value = true
        job = viewModelScope.launch {
            try {
                scanner.scan().collect { r ->
                    found.update {
                        it + (r.device.address to FoundDevice(r.device.address, r.device.name, r.rssi, r.scanRecord?.serviceUuids.toString()))
                    }
                }


            } catch (e: IllegalStateException) {
                // Bluetooth desligado ou scan falhou; mostrar mensagem mais tarde
            } finally {
                _scanning.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun advirtise(){
        if(!scanner.isAdvertising){
            scanner.advertise()
            _advertising.value = true
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvirtise(){
        if(scanner.isAdvertising){
            _advertising.value = false
            scanner.stopAdvertising()
        }
    }

    fun stop() { job?.cancel() }
}