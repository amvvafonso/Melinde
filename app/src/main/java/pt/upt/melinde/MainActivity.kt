package pt.upt.melinde

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.upt.melinde.transport.ble.BleInterface
import pt.upt.melinde.ui.scan.ScanViewModel
import pt.upt.melinde.ui.theme.MelindeTheme

class MainActivity : ComponentActivity() {
    private lateinit var bleInterface: BleInterface
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MelindeTheme {
                MelindeApp()
            }
        }

    }

}


@PreviewScreenSizes
@Composable
fun MelindeApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            painterResource(it.icon),
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination){
                AppDestinations.DISPOSITIVOS -> ScanScreen(Modifier.padding(innerPadding))
                else -> {}
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_home),
    DISPOSITIVOS("Dispositivos", R.drawable.ic_account_box),
}
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel()
) {

    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val advirtising by viewModel.advirtising.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val scanGranted =
            result[Manifest.permission.BLUETOOTH_SCAN] == true

        val connectGranted =
            result[Manifest.permission.BLUETOOTH_CONNECT] == true

        val advertiseGranted =
            result[Manifest.permission.BLUETOOTH_ADVERTISE] == true

        if (scanGranted && connectGranted) {
            viewModel.start()
        }

        if (advertiseGranted){
            viewModel.advirtise()
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        Button(onClick = {
            if (scanning) viewModel.stop()
            else permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            )
        }) {
            Text(if (scanning) "Parar" else "Procurar")
        }
        Button(onClick = {
            if (advirtising) viewModel.stopAdvirtise()
            else permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            )
        }) {
            Text(if (advirtising) "Parar de adv" else "Advirtise")
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn {
            items(devices, key = { it.address }) { d ->
                    Text("${d.name} ")
                }

        }
    }
}
