package io.bearound.bearoundscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import io.bearound.bearoundscan.ui.ContentScreen
import io.bearound.bearoundscan.ui.DetectionLogScreen
import io.bearound.bearoundscan.ui.theme.BeAroundScanTheme
import io.bearound.bearoundscan.viewmodel.BeaconViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BeAroundScanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    BEACONS("Beacons", Icons.Default.Sensors),
    LOG("Log", Icons.Default.ListAlt)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: BeaconViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(Tab.BEACONS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BeAround Scan") }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            Tab.BEACONS -> ContentScreen(viewModel = viewModel, paddingValues = paddingValues)
            Tab.LOG -> DetectionLogScreen(
                viewModel = viewModel,
                paddingValues = paddingValues
            )
        }
    }
}
