package com.example.horizon1

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.horizon1.ui.CameraPreview
import com.example.horizon1.ui.OverlayView

class MainActivity : ComponentActivity() {

    private val sensorViewModel: SensorViewModel by viewModels()
    private val locationViewModel: LocationViewModel by viewModels()

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
            val context = LocalContext.current
            var showManualDialog by remember { mutableStateOf(false) }
            var manualHeadingInput by remember { mutableStateOf("") }
            
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
                )
                if (locationData.hasMovement) {
                    sensorViewModel.calibrateWithGps(
                        locationData.bearing,
                        locationData.speed,
                        locationData.bearingAccuracy
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreview()
                OverlayView(sensorData = sensorData)

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

                // Calibration Control Buttons
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding() // Ensures buttons are above system nav bar
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (sensorData.overlayAlpha > 0.5f) {
                        Button(
                            onClick = { sensorViewModel.captureManualOrientation() },
                            enabled = sensorData.calibrationState == CalibrationState.CALIBRATED
                        ) {
                            Text("MANUAL")
                        }
                    } else {
                        Button(
                            onClick = { showManualDialog = true },
                            enabled = sensorData.calibrationState == CalibrationState.CALIBRATED
                        ) {
                            Text("CAPTURE")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Text("EXIT")
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        locationViewModel.stopLocationUpdates()
    }
}
