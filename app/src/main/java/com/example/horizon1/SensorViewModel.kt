package com.example.horizon1

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

enum class CalibrationState {
    IDLE,
    STATIONARY_WAIT,
    CALIBRATED
}

data class Orientation(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float,
    val fullPitch: Float = 0f // -180 to 180 for continuous vertical line
)

@Immutable
data class SensorData(
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val heading: Float = 0f,
    val headingString: String = "N",
    val truePitch: Float = 0f,
    val trueRoll: Float = 0f,
    val trueFullPitch: Float = 0f,
    val trueHeading: Float = 0f,
    val trueHeadingString: String = "N",
    val gpsHeading: Float? = null,
    val gpsHeadingString: String? = null,
    val manualHeading: Float? = null,
    val manualHeadingString: String? = null,
    val calibrationState: CalibrationState = CalibrationState.IDLE,
    val isGpsCalibrated: Boolean = false,
    val isManualCalibrated: Boolean = false,
    val gpsCalibrationTime: Long = 0,
    val manualCalibrationTime: Long = 0,
    val isFlat: Boolean = false,
    val greenBubbleX: Float = 0f,
    val greenBubbleY: Float = 0f,
    val whiteBubbleX: Float = 0f,
    val whiteBubbleY: Float = 0f,
    val hasBeenCalibrated: Boolean = false,
    val overlayAlpha: Float = 0.8f
)

class SensorViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _uiState = MutableStateFlow(SensorData())
    val uiState: StateFlow<SensorData> = _uiState.asStateFlow()

    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var lastTimestamp: Long = 0

    // Automatic calibration tracking
    private val accelHistory = mutableListOf<Triple<Long, FloatArray, Float>>() // timestamp, values, magnitude
    private var lastCalibrationTime: Long = 0
    private val STABILITY_THRESHOLD = 1.50f // Loosened for non-portrait stability
    private val CALIBRATION_SUSPENSION_MS = 5000L // Reduced for more frequent attempts
    private val CALIBRATION_WINDOW_MS = 1000L

    // Calibration tracking
    private var gpsNorthOffset: Float? = null
    private var manualNorthOffset: Float? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastRawAccel = FloatArray(3)

    // Rotation matrices
    private var fusedMatrix = FloatArray(9) { if ((it % 4) == 0) 1f else 0f }
    private var gyroOnlyMatrix = FloatArray(9) { if ((it % 4) == 0) 1f else 0f }
    private var displayRotation: Int = android.view.Surface.ROTATION_0

    // Filtered true orientation
    private var filteredTruePitch = 0f
    private var filteredTrueRoll = 0f
    private var filteredTrueAzimuth = 0f
    private var filteredTrueFullPitch = 0f

    private val alpha = 0.1f

    init {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_UI)
    }

    fun startOrientationCalibration() {
        // This is now automatic, but we can keep it to force a reset if needed
        _uiState.value = _uiState.value.copy(
            calibrationState = CalibrationState.STATIONARY_WAIT,
            isGpsCalibrated = false,
            isManualCalibrated = false,
            gpsCalibrationTime = 0,
            manualCalibrationTime = 0,
            hasBeenCalibrated = false
        )
        accelHistory.clear()
        lastCalibrationTime = 0
        gpsNorthOffset = null
        manualNorthOffset = null
    }

    fun calibrateWithGps(bearing: Float, speed: Float, bearingAccuracy: Float = 10f) {
        if (speed > 2.0f && bearingAccuracy <= 4.0f) { // Only calibrate if moving and precise
            val gyroOrientation = getOrientationFromMatrix(gyroOnlyMatrix)
            
            // Offset = GpsBearing - CurrentGyroAzimuth
            val offset = (bearing - gyroOrientation.azimuth + 540) % 360 - 180
            
            // Apply a simple low-pass filter if we already have an offset
            gpsNorthOffset = gpsNorthOffset?.let { (0.95f * it) + (0.05f * offset) } ?: offset
            
            _uiState.value = _uiState.value.copy(
                isGpsCalibrated = true,
                gpsCalibrationTime = System.currentTimeMillis()
            )
        }
    }

    fun calibrateWithLandmark(landmarkBearing: Float) {
        if (_uiState.value.calibrationState == CalibrationState.CALIBRATED) {
            val gyroOrientation = getOrientationFromMatrix(gyroOnlyMatrix)
            
            // Offset = LandmarkTrueBearing - CurrentGyroAzimuth
            val offset = (landmarkBearing - gyroOrientation.azimuth + 540) % 360 - 180
            manualNorthOffset = offset
            
            _uiState.value = _uiState.value.copy(
                isManualCalibrated = true,
                manualCalibrationTime = System.currentTimeMillis()
            )
        }
    }

    fun captureManualOrientation() {
        if (_uiState.value.calibrationState == CalibrationState.CALIBRATED) {
            _uiState.value = _uiState.value.copy(overlayAlpha = 0.2f)
        }
    }

    fun finalizeManualCalibration(trueHeading: Float) {
        if (_uiState.value.calibrationState == CalibrationState.CALIBRATED) {
            val gyroOrientation = getOrientationFromMatrix(gyroOnlyMatrix)
            val offset = (trueHeading - gyroOrientation.azimuth + 540) % 360 - 180
            manualNorthOffset = offset
            _uiState.value = _uiState.value.copy(
                overlayAlpha = 0.8f,
                isManualCalibrated = true,
                manualCalibrationTime = System.currentTimeMillis()
            )
        }
    }

    fun cancelManualCalibration() {
        _uiState.value = _uiState.value.copy(overlayAlpha = 0.8f)
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastRawAccel = event.values.clone()
                gravity = if (gravity == null) event.values.clone() else applyLowPassFilter(event.values, gravity!!)
                handleAutomaticCalibration(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic = if (geomagnetic == null) event.values.clone() else applyLowPassFilter(event.values, geomagnetic!!)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (lastTimestamp != 0L) {
                    val dt = (event.timestamp - lastTimestamp) * 1e-9f
                    val deltaR = getDeltaRotation(event.values, dt)
                    fusedMatrix = multiplyMatrix(fusedMatrix, deltaR)
                    normalizeMatrix(fusedMatrix)
                    
                    if (_uiState.value.calibrationState == CalibrationState.CALIBRATED) {
                        gyroOnlyMatrix = multiplyMatrix(gyroOnlyMatrix, deltaR)
                        normalizeMatrix(gyroOnlyMatrix)
                    }
                }
                lastTimestamp = event.timestamp
            }
        }
        updateUiState()
    }

    private fun handleAutomaticCalibration(values: FloatArray) {
        val currentTime = System.currentTimeMillis()
        
        // 1. Suspension check
        if (lastCalibrationTime != 0L && (currentTime - lastCalibrationTime) < CALIBRATION_SUSPENSION_MS) {
            accelHistory.clear()
            return
        }

        // 2. Window management
        val magnitude = sqrt(values[0]*values[0] + values[1]*values[1] + values[2]*values[2])
        accelHistory.add(Triple(currentTime, values.clone(), magnitude))
        
        // Remove old samples
        while (accelHistory.isNotEmpty() && (currentTime - accelHistory.first().first) > CALIBRATION_WINDOW_MS) {
            accelHistory.removeAt(0)
        }

        // 3. Stability check (need at least 1 second of data)
        if (accelHistory.isNotEmpty() && (currentTime - accelHistory.first().first) >= CALIBRATION_WINDOW_MS) {
            // Calculate average vector
            var avgX = 0f; var avgY = 0f; var avgZ = 0f
            for (sample in accelHistory) {
                avgX += sample.second[0]; avgY += sample.second[1]; avgZ += sample.second[2]
            }
            avgX /= accelHistory.size; avgY /= accelHistory.size; avgZ /= accelHistory.size

            // Calculate max deviation from average (Vector Jitter)
            var maxDevSq = 0f
            for (sample in accelHistory) {
                val dx = sample.second[0] - avgX; val dy = sample.second[1] - avgY; val dz = sample.second[2] - avgZ
                val devSq = dx*dx + dy*dy + dz*dz
                if (devSq > maxDevSq) maxDevSq = devSq
            }

            // If the maximum vector deviation is within threshold, device is stationary
            // Threshold is squared here for efficiency: 0.1g ~ 0.98 m/s^2 -> 0.98^2 ~ 0.96
            if (maxDevSq <= (STABILITY_THRESHOLD * STABILITY_THRESHOLD)) {
                // Snap together: Copy fused (accelerometer-based) to gyroOnly
                gyroOnlyMatrix = fusedMatrix.clone()
                lastCalibrationTime = currentTime
                _uiState.value = _uiState.value.copy(
                    calibrationState = CalibrationState.CALIBRATED,
                    hasBeenCalibrated = true
                )
                accelHistory.clear()
            }
        }
    }

    private fun updateUiState() {
        if ((gravity != null) && (geomagnetic != null)) {
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                val alphaFuse = 0.98f 
                for (j in fusedMatrix.indices) fusedMatrix[j] = (alphaFuse * fusedMatrix[j]) + ((1 - alphaFuse) * r[j])
                normalizeMatrix(fusedMatrix)
            }
        }
        
        // 1. Detect Flat orientation: abs(Z) > 10 * (abs(X) + abs(Y))
        val isFlat = abs(lastRawAccel[2]) > 10 * (abs(lastRawAccel[0]) + abs(lastRawAccel[1]))

        val fusedOrientation = getOrientationFromMatrix(fusedMatrix)
        val isCalibrated = _uiState.value.calibrationState == CalibrationState.CALIBRATED
        val gyroOrientation = getOrientationFromMatrix(if (isCalibrated) gyroOnlyMatrix else fusedMatrix)

        if (!fusedOrientation.pitch.isNaN() && !gyroOrientation.pitch.isNaN()) {
            val manualRecent = _uiState.value.manualCalibrationTime > _uiState.value.gpsCalibrationTime
            val bestOffset = if (manualRecent) {
                manualNorthOffset ?: gpsNorthOffset
            } else {
                gpsNorthOffset ?: manualNorthOffset
            }
            
            val currentGpsHeading = gpsNorthOffset?.let { (gyroOrientation.azimuth + it + 360) % 360 }
            val currentManualHeading = manualNorthOffset?.let { (gyroOrientation.azimuth + it + 360) % 360 }

            // Apply low-pass filter to true orientation elements
            filteredTruePitch = filteredTruePitch + alpha * (gyroOrientation.pitch - filteredTruePitch)
            filteredTrueRoll = filteredTrueRoll + alpha * (gyroOrientation.roll - filteredTrueRoll)
            filteredTrueFullPitch = filteredTrueFullPitch + alpha * (gyroOrientation.fullPitch - filteredTrueFullPitch)
            
            // For azimuth, we need to handle wrapping correctly
            val azDiff = (gyroOrientation.azimuth - filteredTrueAzimuth + 540) % 360 - 180
            filteredTrueAzimuth = (filteredTrueAzimuth + alpha * azDiff + 360) % 360

            val currentTrueHeading = bestOffset?.let { (filteredTrueAzimuth + it + 360) % 360 }

            // Bubble offsets: Projected World Z onto Device XY
            val gBX = Math.toDegrees(asin(fusedMatrix[6].toDouble())).toFloat()
            val gBY = Math.toDegrees(asin(fusedMatrix[7].toDouble())).toFloat()
            val currentGyroMatrix = if (isCalibrated) gyroOnlyMatrix else fusedMatrix
            val wBX = Math.toDegrees(asin(currentGyroMatrix[6].toDouble())).toFloat()
            val wBY = Math.toDegrees(asin(currentGyroMatrix[7].toDouble())).toFloat()

            _uiState.value = _uiState.value.copy(
                pitch = fusedOrientation.pitch, roll = fusedOrientation.roll, heading = fusedOrientation.azimuth, headingString = getHeadingString(fusedOrientation.azimuth),
                truePitch = filteredTruePitch, trueRoll = filteredTrueRoll, trueFullPitch = filteredTrueFullPitch,
                trueHeading = currentTrueHeading ?: 0f,
                trueHeadingString = currentTrueHeading?.let { getHeadingString(it) } ?: "N",
                gpsHeading = currentGpsHeading,
                gpsHeadingString = currentGpsHeading?.let { getHeadingString(it) },
                manualHeading = currentManualHeading,
                manualHeadingString = currentManualHeading?.let { getHeadingString(it) },
                isFlat = isFlat,
                greenBubbleX = gBX, greenBubbleY = gBY,
                whiteBubbleX = wBX, whiteBubbleY = wBY
            )
        }
    }

    fun addLocationData(lat: Double, lon: Double) {
        lastLat = lat; lastLon = lon
    }

    private fun normalizeMatrix(m: FloatArray) {
        val x = floatArrayOf(m[0], m[1], m[2]); val y = floatArrayOf(m[3], m[4], m[5])
        var magX = sqrt(x[0]*x[0] + x[1]*x[1] + x[2]*x[2]); if (magX == 0f) magX = 1f
        x[0]/=magX; x[1]/=magX; x[2]/=magX
        val dotXY = x[0]*y[0] + x[1]*y[1] + x[2]*y[2]
        y[0] -= dotXY*x[0]; y[1] -= dotXY*x[1]; y[2] -= dotXY*x[2]
        var magY = sqrt(y[0]*y[0] + y[1]*y[1] + y[2]*y[2]); if (magY == 0f) magY = 1f
        y[0]/=magY; y[1]/=magY; y[2]/=magY
        val z0 = x[1]*y[2] - x[2]*y[1]; val z1 = x[2]*y[0] - x[0]*y[2]; val z2 = x[0]*y[1] - x[1]*y[0]
        m[0]=x[0]; m[1]=x[1]; m[2]=x[2]; m[3]=y[0]; m[4]=y[1]; m[5]=y[2]; m[6]=z0; m[7]=z1; m[8]=z2
    }

    private fun getOrientationFromMatrix(matrix: FloatArray): Orientation {
        // 1. Account for display rotation by remapping the coordinate system
        // such that X is Screen Right, Y is Screen Top, Z is Screen Out (towards user).
        val remapped = FloatArray(9)
        when (displayRotation) {
            android.view.Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remapped)
            android.view.Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            android.view.Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
            android.view.Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            else -> matrix.copyInto(remapped)
        }

        // Camera Forward Vf (pointing out the back) is Screen -Z.
        // Vf_world = R_remapped * [0, 0, -1] = [-R[2], -R[5], -R[8]]
        val vfx = -remapped[2]
        val vfy = -remapped[5]
        val vfz = -remapped[8]
        
        // Pitch: Angle between Vf and ground plane. Positive = Up.
        val pitch = Math.toDegrees(asin(vfz.toDouble())).toFloat()
        
        val azimuth: Float
        val roll: Float
        val fullPitch: Float
        
        // Check for vertical orientation (gimbal lock) where Screen Forward is parallel to World Z
        if (abs(vfz) > 0.99f) { 
            // Looking straight down or up. Azimuth is defined by Screen Top (Dy).
            azimuth = (Math.toDegrees(atan2(remapped[1].toDouble(), remapped[4].toDouble())).toFloat() + 360) % 360
            roll = 0f
            fullPitch = pitch
        } else {
            // Azimuth: Bearing of Screen Forward projection on ground
            azimuth = (Math.toDegrees(atan2(vfx.toDouble(), vfy.toDouble())).toFloat() + 360) % 360
            
            // Roll: Rotation of device around Screen Forward.
            // Project World Up [0,0,1] onto Screen XY plane.
            // Component along Screen X = remapped[6]
            // Component along Screen Y = remapped[7]
            // Positive roll is counter-clockwise (tilt top-left).
            roll = Math.toDegrees(atan2(remapped[6].toDouble(), remapped[7].toDouble())).toFloat()
            
            // fullPitch: handle turnover. If inverted (abs(roll) > 90), pitch is "behind"
            fullPitch = if (abs(roll) > 90f) {
                if (pitch > 0) 180f - pitch else -180f - pitch
            } else {
                pitch
            }
        }
        
        return Orientation(azimuth, pitch, roll, fullPitch)
    }

    private fun getDeltaRotation(gyroValues: FloatArray, dt: Float): FloatArray {
        val deltaR = FloatArray(9); val omega = sqrt(gyroValues[0] * gyroValues[0] + gyroValues[1] * gyroValues[1] + gyroValues[2] * gyroValues[2])
        if (omega > 1e-9f) {
            val t2 = (omega * dt) / 2.0f; val sT = sin(t2.toDouble()).toFloat(); val cT = cos(t2.toDouble()).toFloat()
            SensorManager.getRotationMatrixFromVector(deltaR, floatArrayOf((gyroValues[0]/omega)*sT, (gyroValues[1]/omega)*sT, (gyroValues[2]/omega)*sT, cT))
        } else for (i in 0..8) deltaR[i] = if ((i % 4) == 0) 1f else 0f
        return deltaR
    }

    private fun multiplyMatrix(a: FloatArray, b: FloatArray): FloatArray {
        val res = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[(i * 3) + k] * b[(k * 3) + j]
            res[(i * 3) + j] = s
        }
        return res
    }

    private fun applyLowPassFilter(input: FloatArray, output: FloatArray): FloatArray {
        for (i in input.indices) output[i] = output[i] + (alpha * (input[i] - output[i]))
        return output
    }

    private fun getHeadingString(degrees: Float): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return "${degrees.toInt()}° ${dirs[((degrees + 11.25) / 22.5).toInt() % 16]}"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onCleared() { super.onCleared(); sensorManager.unregisterListener(this) }
}
