package com.example.horizon1

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

data class AirportData(
    val code: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double,
    val pressureHpa: Float,
    val distanceKm: Double = 0.0,
    val weight: Double = 0.0
)

data class AltimeterData(
    val rawPressureHpa: Int = 1013,
    val correctedAltitudeM: Int = 0,
    val airports: List<AirportData> = emptyList(),
    val isDetailVisible: Boolean = false,
    val lastFetchTime: Long = 0
)

class AltimeterViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val _uiState = MutableStateFlow(AltimeterData())
    val uiState = _uiState.asStateFlow()

    private var currentPressure = 1013.25f
    private var lastLocation: Pair<Double, Double>? = null

    // Reference Airport Database (Subset)
    private val airportDb = listOf(
        AirportData("KJFK", "John F. Kennedy", 40.6413, -73.7781, 4.0, 1013.25f),
        AirportData("KLAX", "Los Angeles Intl", 33.9416, -118.4085, 38.0, 1013.25f),
        AirportData("KORD", "O'Hare Intl", 41.9742, -87.9073, 204.0, 1013.25f),
        AirportData("KATL", "Hartsfield-Jackson", 33.6407, -84.4277, 313.0, 1013.25f),
        AirportData("EGLL", "London Heathrow", 51.4700, -0.4543, 25.0, 1013.25f),
        AirportData("LFPG", "Paris Charles de Gaulle", 49.0097, 2.5479, 119.0, 1013.25f),
        AirportData("VHHH", "Hong Kong Intl", 22.3089, 113.9145, 9.0, 1013.25f),
        AirportData("KSFO", "San Francisco Intl", 37.6213, -122.3790, 4.0, 1013.25f)
    )

    init {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        startAirportUpdateLoop()
    }

    private fun startAirportUpdateLoop() {
        viewModelScope.launch {
            while (true) {
                updateCorrectedAltitude()
                delay(100000L) // 100 seconds
            }
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        lastLocation = lat to lon
        updateCorrectedAltitude()
    }

    fun toggleDetail() {
        _uiState.value = _uiState.value.copy(isDetailVisible = !_uiState.value.isDetailVisible)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            currentPressure = event.values[0]
            _uiState.value = _uiState.value.copy(rawPressureHpa = currentPressure.toInt())
            updateCorrectedAltitude()
        }
    }

    private fun updateCorrectedAltitude() {
        val location = lastLocation ?: return
        
        // 1. Find 5 nearest airports
        val nearest = airportDb.map {
            val dist = calculateDistance(location.first, location.second, it.latitude, it.longitude)
            it.copy(distanceKm = dist)
        }.sortedBy { it.distanceKm }.take(5)

        // 2. Weighted average pressure (QNH)
        // Weight = 1 / (dist^2 + 1)
        var totalWeight = 0.0
        var weightedSum = 0.0
        val finalAirports = nearest.map {
            val weight = 1.0 / (it.distanceKm * it.distanceKm + 1.0)
            totalWeight += weight
            weightedSum += it.pressureHpa * weight
            it.copy(weight = weight)
        }

        val avgSeaPressure = if (totalWeight > 0) (weightedSum / totalWeight).toFloat() else 1013.25f

        // 3. Barometric Formula: Alt = 44330 * (1 - (Pcurr / Psea)^(1/5.255))
        val altitude = 44330 * (1.0 - (currentPressure / avgSeaPressure).toDouble().pow(1.0 / 5.255))

        _uiState.value = _uiState.value.copy(
            correctedAltitudeM = altitude.toInt(),
            airports = finalAirports,
            lastFetchTime = System.currentTimeMillis()
        )
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onCleared() {
        super.onCleared()
        sensorManager.unregisterListener(this)
    }
}
