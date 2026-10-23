
package pt.upt.melinde.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.upt.melinde.transport.Message
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
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    var chatBox by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableStateOf<String?>(null) }

    val blue = Color(0xFF5278F5)
    val background = Color(0xFFF4F5F9)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .padding(12.dp)
    ) {
        // Header
        Text(
            text = "Melinde Chat",
            fontSize = 24.sp,
            color = blue
        )

        Spacer(Modifier.height(12.dp))
        Text(viewModel.nodeId.toString())
        // Scan and advertising buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (scanning) viewModel.stopScan()
                    else viewModel.startScan()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (scanning) "Parar scan" else "Procurar")
            }

            Button(
                onClick = {
                    if (advertising) viewModel.stopAdvertising()
                    else viewModel.startAdvertising()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (advertising) "Parar anúncio" else "Anunciar")
            }
        }

        error?.let {
            Text(
                text = "Erro: $it",
                color = Color.Red,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Device selection
        Text(
            text = "Dispositivos encontrados",
            fontSize = 16.sp
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            devices.forEach { device ->
                OutlinedButton(
                    onClick = { selectedDevice = device.deviceId.toString() },
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(
                        text = if (selectedDevice == device.deviceId.toString()) "✓ ${device.deviceId} - ${device.rssi}" else "${device.deviceId.toString()} - - ${device.rssi}",
                        fontSize = 11.sp
                    )

                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Chat area
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Conversa",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }

            // Mensagens recebidas do ViewModel.
            // Ajusta esta parte à estrutura real de Message.
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = message.toString(),
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomEnd = 16.dp,
                                    bottomStart = 4.dp
                                )
                            )
                            .background(Color.White)
                            .padding(12.dp),
                        color = Color.DarkGray,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Message input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = chatBox,
                onValueChange = { chatBox = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Escreve uma mensagem...")
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    val deviceId = selectedDevice
                    if (deviceId != null && chatBox.isNotBlank()) {
                        val device = devices.firstOrNull {
                            it.deviceId.toString() == deviceId
                        }

                        if (device != null) {
                            viewModel.send(
                                device,
                                Message(
                                    UUID.randomUUID().toString(),
                                    device.deviceId,
                                    device.deviceId,
                                    chatBox.toByteArray(Charsets.UTF_8)
                                )
                            )
                            chatBox = ""
                        }
                    }
                },
                enabled = selectedDevice != null &&
                        chatBox.isNotBlank()
            ) {
                Text("Enviar")
            }
        }
    }
}
