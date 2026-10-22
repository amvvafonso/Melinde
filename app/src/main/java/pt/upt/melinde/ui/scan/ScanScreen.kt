package pt.upt.melinde.ui.scan

import android.graphics.Color
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.upt.melinde.transport.Message
import pt.upt.melinde.transport.ble.GattServer
import java.util.UUID


@Composable
    fun ScanScreen(
        modifier: Modifier = Modifier,
        viewModel: ScanViewModel = viewModel()
    ) {
        val devices by viewModel.devices.collectAsStateWithLifecycle()
        val scanning by viewModel.scanning.collectAsStateWithLifecycle()
        val advertising by viewModel.advertising.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()

        Column(modifier = modifier.padding(16.dp)) {
            Button(onClick = {
                if (scanning) viewModel.stopScan() else viewModel.startScan()
            }) {
                Text(if (scanning) "Parar scan" else "Procurar")
            }

            Button(onClick = {
                if (advertising) viewModel.stopAdvertising() else viewModel.startAdvertising()
            }) {
                Text(if (advertising) "Parar anúncio" else "Anunciar")
            }

            error?.let { Text("Erro: $it") }

            Spacer(Modifier.height(100.dp))

            if (devices.size != 0) {
                Text("Dispositivos encontrados:")
            }
            LazyColumn {
                items(devices, key = { it.deviceId }) { d ->
                   Button(
                       { viewModel.send(d, Message(UUID.randomUUID().toString(), d.deviceId, d.deviceId, "Ola".toByteArray())) }
                   ) { Text("${d.deviceId}") }
                }
            }
        }
    }

