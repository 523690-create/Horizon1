package com.example.horizon3

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.horizon3.ui.CameraPreview
import com.example.horizon3.ui.OverlayView
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    enum class AppMode {
        ALTIMETER,
        PLANES,
        STARS,
        SETTINGS,
        CALIBRATE
    }

    private val sensorViewModel: SensorViewModel by viewModels()
    private val locationViewModel: LocationViewModel by viewModels()
    private val altimeterViewModel: AltimeterViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locationViewModel.startLocationUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )

        setContent {
            val sensorData by sensorViewModel.uiState.collectAsStateWithLifecycle()
            val locationData by locationViewModel.locationState.collectAsStateWithLifecycle()
            val altimeterData by altimeterViewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var showManualDialog by remember { mutableStateOf(false) }
            var manualHeadingInput by remember { mutableStateOf("") }
            var showSettings by remember { mutableStateOf(false) }
            var selectedMode by remember { mutableStateOf(AppMode.ALTIMETER) }
            var radioButtonsHeight by remember { mutableStateOf(0) }

            LaunchedEffect(selectedMode) {
                sensorViewModel.setAppMode(selectedMode.name)
                altimeterViewModel.setAppMode(selectedMode.name)
            }


            // Update display rotation
            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.display.rotation
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(WindowManager::class.java))?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
            }
            sensorViewModel.setDisplayRotation(rotation)

            // Feed location for GPS calibration and refinement
            LaunchedEffect(locationData) {
                sensorViewModel.addLocationData(
                    locationData.latitude,
                    locationData.longitude,
                    locationData.speed,
                    locationData.bearing
                )
                altimeterViewModel.updateLocation(
                    locationData.latitude,
                    locationData.longitude
                )
                if (locationData.hasMovement) {
                    sensorViewModel.calibrateWithGps(
                        locationData.bearing,
                        locationData.speed,
                        locationData.bearingAccuracy
                    )
                }
            }

            LaunchedEffect(altimeterData.status) {
                sensorViewModel.updateMetarStatus(altimeterData.status)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreview()
                OverlayView(
                    sensorData = sensorData,
                    altimeterData = altimeterData,
                    onAltimeterClick = { altimeterViewModel.toggleDetail() },
                    onCaptureClick = { showManualDialog = true },
                    onPlanesDistanceChange = { sensorViewModel.updatePlanesDistance(it) },
                    onVerboseToggle = { sensorViewModel.toggleVerbose() },
                    onGroundedToggle = { sensorViewModel.toggleGrounded() },
                    onConstellationToggle = { sensorViewModel.toggleConstellations() },
                    onRadarClick = { sensorViewModel.resetFovCalibration() },
                    onFovPointClick = { sensorViewModel.captureFovPoint(it, context.resources.displayMetrics.widthPixels.toFloat()) },
                    onStartManualCal = { sensorViewModel.captureManualOrientation() },
                    onCancelManualCal = { sensorViewModel.cancelManualCalibration() },
                    appMode = selectedMode.name
                )

                if (showManualDialog) {
                    AlertDialog(
                        onDismissRequest = { 
                            showManualDialog = false
                            sensorViewModel.cancelManualCalibration()
                        },
                        title = { Text("Manual Calibration") },
                        text = {
                            Column {
                                Text("Enter the True Bearing (0-359°) of the object you are aiming at:")
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = manualHeadingInput,
                                    onValueChange = { manualHeadingInput = it.filter { char -> char.isDigit() } },
                                    label = { Text("Degrees") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val heading = manualHeadingInput.toFloatOrNull()
                                    if (heading != null) {
                                        sensorViewModel.finalizeManualCalibration(heading)
                                        showManualDialog = false
                                        manualHeadingInput = ""
                                    }
                                }
                            ) {
                                Text("CALIBRATE")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { 
                                    showManualDialog = false
                                    sensorViewModel.cancelManualCalibration()
                                }
                            ) {
                                Text("CANCEL")
                            }
                        }
                    )
                }

                if (showSettings) {
                    SettingsDialog(
                        currentDelay = sensorData.sensorDelay,
                        currentAlpha = sensorData.overlayAlpha,
                        currentFov = sensorData.hFov,
                        onDismiss = { showSettings = false },
                        onUpdateDelay = { sensorViewModel.updateSensorDelay(it) },
                        onUpdateAlpha = { sensorViewModel.updateOverlayAlpha(it) },
                        onUpdateFov = { sensorViewModel.updateHFov(it) }
                    )
                }

                // Calibration Control Buttons
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding() // Ensures buttons are above system nav bar
                        .padding(bottom = 32.dp, end = 16.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.End,
                ) {
                    AppMode.values().forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                selectedMode = mode
                                if (mode == AppMode.SETTINGS) showSettings = true
                            }
                        ) {
                            Text(
                                text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = Color.White
                            )
                            RadioButton(
                                selected = selectedMode == mode,
                                onClick = {
                                    selectedMode = mode
                                    if (mode == AppMode.SETTINGS) showSettings = true
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color.Yellow,
                                    unselectedColor = Color.White
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Text("EXIT")
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorViewModel.pauseSensors()
        altimeterViewModel.pauseSensors()
        locationViewModel.stopLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        sensorViewModel.resumeSensors()
        altimeterViewModel.resumeSensors()
        locationViewModel.startLocationUpdates()
    }
}

@Composable
fun SettingsDialog(
    currentDelay: Int,
    currentAlpha: Float,
    currentFov: Float,
    onDismiss: () -> Unit,
    onUpdateDelay: (Int) -> Unit,
    onUpdateAlpha: (Float) -> Unit,
    onUpdateFov: (Float) -> Unit
) {
    var selectedDelay by remember { mutableStateOf(currentDelay) }
    var selectedAlpha by remember { mutableStateOf(currentAlpha) }
    var selectedFov by remember { mutableStateOf(currentFov) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Bottom Dialog
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Sensor Delay")
                    Row {
                        Button(onClick = { selectedDelay = android.hardware.SensorManager.SENSOR_DELAY_FASTEST }) { Text("Fast") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = { selectedDelay = android.hardware.SensorManager.SENSOR_DELAY_GAME }) { Text("Game") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = { selectedDelay = android.hardware.SensorManager.SENSOR_DELAY_UI }) { Text("UI") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Overlay Alpha: ${"%.2f".format(selectedAlpha)}")
                    Slider(
                        value = selectedAlpha, 
                        onValueChange = { 
                            selectedAlpha = it 
                            onUpdateAlpha(it) // Live update
                        }, 
                        valueRange = 0.1f..1.0f
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("CANCEL") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            onUpdateDelay(selectedDelay)
                            onUpdateAlpha(selectedAlpha)
                            onUpdateFov(selectedFov)
                            onDismiss()
                        }) {
                            Text("SAVE")
                        }
                    }
                }
            }
        }
    }
}
