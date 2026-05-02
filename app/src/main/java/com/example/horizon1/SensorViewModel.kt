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
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
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
)

@Immutable
data class SensorData(
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val heading: Float = 0f,
    val headingString: String = "N",
    val truePitch: Float = 0f,
    val trueRoll: Float = 0f,
    val trueHeading: Float = 0f,
    val calibrationState: CalibrationState = CalibrationState.IDLE,
    val solarAzimuth: Double? = null,
    val solarAltitude: Double? = null,
    val isSunCalibrated: Boolean = false,
    val overlayAlpha: Float = 0.8f
)

class SensorViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private val _uiState = MutableStateFlow(SensorData())
    val uiState: StateFlow<SensorData> = _uiState.asStateFlow()

    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var lastTimestamp: Long = 0

    // Stationary detection
    private val accelWindow = mutableListOf<Float>()
    private var stationaryStartTime: Long = 0
    private var filteredLinearAccel = FloatArray(3)
    private val alpha6Hz = 0.43f 

    // Calibration tracking
    private var trueNorthOffset: Float? = null 
    private var lastLat = 0.0
    private var lastLon = 0.0

    // Rotation matrices
    private var fusedMatrix = FloatArray(9) { if ((it % 4) == 0) 1f else 0f }
    private var gyroOnlyMatrix = FloatArray(9) { if ((it % 4) == 0) 1f else 0f }
    private var displayRotation: Int = android.view.Surface.ROTATION_0

    private val alpha = 0.1f

    init {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, linearAccel, SensorManager.SENSOR_DELAY_UI)
    }

    fun startOrientationCalibration() {
        _uiState.value = _uiState.value.copy(
            calibrationState = CalibrationState.STATIONARY_WAIT,
            isSunCalibrated = false
        )
        accelWindow.clear()
        stationaryStartTime = 0
        trueNorthOffset = null
    }

    fun calibrateWithSun() {
        if (_uiState.value.calibrationState == CalibrationState.CALIBRATED) {
            val sunPos = calculateSolarPosition(lastLat, lastLon, ZonedDateTime.now())
            val gyroOrientation = getOrientationFromMatrix(gyroOnlyMatrix)
            
            // Offset = SunTrueAzimuth - CurrentGyroAzimuth
            val offset = (sunPos.azimuth.toFloat() - gyroOrientation.azimuth + 540) % 360 - 180
            trueNorthOffset = offset
            
            _uiState.value = _uiState.value.copy(
                solarAzimuth = sunPos.azimuth,
                solarAltitude = sunPos.elevation,
                isSunCalibrated = true
            )
        }
    }

    fun calibrateWithLandmark(landmarkBearing: Float) {
        if (_uiState.value.calibrationState == CalibrationState.CALIBRATED) {
            val gyroOrientation = getOrientationFromMatrix(gyroOnlyMatrix)
            
            // Offset = LandmarkTrueBearing - CurrentGyroAzimuth
            val offset = (landmarkBearing - gyroOrientation.azimuth + 540) % 360 - 180
            trueNorthOffset = offset
            
            _uiState.value = _uiState.value.copy(
                isSunCalibrated = true // Reuse this flag to show "True" info
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
            trueNorthOffset = offset
            _uiState.value = _uiState.value.copy(
                overlayAlpha = 0.8f,
                isSunCalibrated = true
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
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAccel(event.values)
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = if (gravity == null) event.values.clone() else applyLowPassFilter(event.values, gravity!!)
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

    private fun handleLinearAccel(values: FloatArray) {
        for (i in 0..2) filteredLinearAccel[i] = filteredLinearAccel[i] + (alpha6Hz * (values[i] - filteredLinearAccel[i]))
        if (_uiState.value.calibrationState == CalibrationState.STATIONARY_WAIT) {
            val magnitude = sqrt((filteredLinearAccel[0] * filteredLinearAccel[0]) + (filteredLinearAccel[1] * filteredLinearAccel[1]) + (filteredLinearAccel[2] * filteredLinearAccel[2]))
            accelWindow.add(magnitude)
            if (accelWindow.size > 50) accelWindow.removeAt(0)
            if (accelWindow.size == 50) {
                val mean = accelWindow.average().toFloat()
                val variance = accelWindow.asSequence().map { (it - mean) * (it - mean) }.average().toFloat()
                val stdDev = sqrt(variance)
                if (stdDev < 0.098f) { 
                    if (stationaryStartTime == 0L) stationaryStartTime = System.currentTimeMillis()
                    else if ((System.currentTimeMillis() - stationaryStartTime) > 1000) {
                        gyroOnlyMatrix = fusedMatrix.clone()
                        _uiState.value = _uiState.value.copy(calibrationState = CalibrationState.CALIBRATED)
                    }
                } else stationaryStartTime = 0
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
        val fusedOrientation = getOrientationFromMatrix(fusedMatrix)
        val isCalibrated = _uiState.value.calibrationState == CalibrationState.CALIBRATED
        val gyroOrientation = getOrientationFromMatrix(if (isCalibrated) gyroOnlyMatrix else fusedMatrix)

        if (!fusedOrientation.pitch.isNaN() && !gyroOrientation.pitch.isNaN()) {
            val currentTrueHeading = trueNorthOffset?.let { (gyroOrientation.azimuth + it + 360) % 360 }
            _uiState.value = _uiState.value.copy(
                pitch = fusedOrientation.pitch, roll = fusedOrientation.roll, heading = fusedOrientation.azimuth, headingString = getHeadingString(fusedOrientation.azimuth),
                truePitch = gyroOrientation.pitch, trueRoll = gyroOrientation.roll, trueHeading = currentTrueHeading ?: 0f
            )
        }
    }

    fun addLocationData(lat: Double, lon: Double) {
        lastLat = lat; lastLon = lon
    }

    private fun calculateSolarPosition(latitude: Double, longitude: Double, dateTime: ZonedDateTime): SolarPosition {
        val utcTime = dateTime.withZoneSameInstant(ZoneOffset.UTC)
        val epoch = ZonedDateTime.of(2000, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        val daysSinceJ2000 = ChronoUnit.MILLIS.between(epoch, utcTime).toDouble() / (1000.0 * 60 * 60 * 24)
        val t = daysSinceJ2000 / 36525.0

        var l0 = 280.46646 + t * (36000.76983 + t * 0.0003032)
        var m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        l0 %= 360.0; m %= 360.0

        val mRad = Math.toRadians(m)
        val c = (1.914602 - t * (0.004817 + 0.000014 * t)) * sin(mRad) + (0.019993 - 0.000101 * t) * sin(2 * mRad) + 0.000289 * sin(3 * mRad)
        val sunApparentLong = l0 + c - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * t))

        val epsilon0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val epsilon = epsilon0 + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * t))

        val declination = Math.toDegrees(asin(sin(Math.toRadians(epsilon)) * sin(Math.toRadians(sunApparentLong))))
        val y = tan(Math.toRadians(epsilon) / 2.0).pow(2)
        val l0Rad = Math.toRadians(l0)
        val eqTime = 4.0 * Math.toDegrees(y * sin(2 * l0Rad) - 2.0 * 0.016708 * sin(mRad) + 4.0 * 0.016708 * y * sin(mRad) * cos(2 * l0Rad) - 0.5 * y * y * sin(4 * l0Rad) - 1.25 * 0.016708 * 0.016708 * sin(2 * mRad))

        val timeInMinutes = utcTime.hour * 60.0 + utcTime.minute + utcTime.second / 60.0
        val trueSolarTime = (timeInMinutes + eqTime + 4.0 * longitude) % 1440.0
        val hourAngle = (if (trueSolarTime < 0) trueSolarTime + 1440.0 else trueSolarTime) / 4.0 - 180.0

        val latRad = Math.toRadians(latitude); val declRad = Math.toRadians(declination); val haRad = Math.toRadians(hourAngle)
        val zenithRad = acos(sin(latRad) * sin(declRad) + cos(latRad) * cos(declRad) * cos(haRad))
        val elevation = 90.0 - Math.toDegrees(zenithRad)

        var azimuthRad = acos(((sin(declRad) * cos(latRad)) - (cos(haRad) * cos(declRad) * sin(latRad))) / sin(zenithRad))
        var azimuth = Math.toDegrees(azimuthRad)
        azimuth = if (hourAngle > 0) (azimuth + 180) % 360 else (540 - azimuth) % 360

        return SolarPosition(elevation, azimuth)
    }

    private data class SolarPosition(val elevation: Double, val azimuth: Double)

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
        val remappedR = FloatArray(9)
        when (displayRotation) {
            android.view.Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedR)
            android.view.Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remappedR)
            android.view.Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, remappedR)
            android.view.Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, remappedR)
            else -> SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedR)
        }
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remappedR, orientation)
        return Orientation((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360) % 360, Math.toDegrees(orientation[1].toDouble()).toFloat(), Math.toDegrees(orientation[2].toDouble()).toFloat())
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
