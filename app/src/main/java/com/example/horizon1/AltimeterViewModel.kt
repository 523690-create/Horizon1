package com.example.horizon1

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.*

@Serializable
data class CheckWxResponse(
    val data: List<MetarData>
)

@Serializable
data class MetarData(
    val icao: String? = null,
    val station: StationInfo? = null,
    val barometer: BarometerInfo? = null
)

@Serializable
data class StationInfo(
    val name: String? = null,
    val geometry: GeometryInfo? = null,
    val elevation: ElevationInfo? = null
)

@Serializable
data class GeometryInfo(
    val coordinates: List<Double>? = null // [Lon, Lat]
)

@Serializable
data class ElevationInfo(
    val meters: Double? = null
)

@Serializable
data class BarometerInfo(
    val hpa: Float? = null
)

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
    val lastFetchTime: Long = 0,
    val status: String = "Initializing..."
)

class AltimeterViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val _uiState = MutableStateFlow(AltimeterData())
    val uiState = _uiState.asStateFlow()

    private var currentPressure = 1013.25f
    private var lastLocation: Pair<Double, Double>? = null

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                isLenient = true
            })
        }
    }

    // Replace with a real key if available
    private val API_KEY = "646399ba0920406085a36398f7" 

    init {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        startAirportUpdateLoop()
    }

    private fun startAirportUpdateLoop() {
        viewModelScope.launch {
            while (true) {
                lastLocation?.let { (lat, lon) ->
                    fetchMetarData(lat, lon)
                }
                delay(600000L) // 10 minutes
            }
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return // Skip invalid/initial location

        val isFirstLocation = lastLocation == null
        lastLocation = lat to lon
        Log.d("AltimeterViewModel", "Location updated: $lat, $lon. isFirst: $isFirstLocation")
        if (isFirstLocation) {
            viewModelScope.launch { fetchMetarData(lat, lon) }
        }
    }

    private suspend fun fetchMetarData(lat: Double, lon: Double) {
        try {
            Log.d("AltimeterViewModel", "Starting fetch for $lat, $lon")
            _uiState.value = _uiState.value.copy(status = "Fetching METAR...")
            val url = "https://api.checkwx.com/metar/$lat/$lon/radius/50/decoded"
            Log.d("AltimeterViewModel", "URL: $url")
            
            val response: CheckWxResponse = client.get(url) {
                header("X-API-Key", API_KEY)
            }.body()

            Log.d("AltimeterViewModel", "Response received: ${response.data.size} airports")

            val fetchedAirports = response.data.mapNotNull { metar ->
                val coords = metar.station?.geometry?.coordinates
                val pressure = metar.barometer?.hpa
                if (coords != null && coords.size >= 2 && pressure != null) {
                    val aLat = coords[1]
                    val aLon = coords[0]
                    val dist = calculateDistance(lat, lon, aLat, aLon)
                    AirportData(
                        code = metar.icao ?: "???",
                        name = metar.station.name ?: "Unknown",
                        latitude = aLat,
                        longitude = aLon,
                        elevationM = metar.station.elevation?.meters ?: 0.0,
                        pressureHpa = pressure,
                        distanceKm = dist
                    )
                } else null
            }.sortedBy { it.distanceKm }.take(5)

            if (fetchedAirports.isNotEmpty()) {
                updateAltimeterWithAirports(fetchedAirports)
                _uiState.value = _uiState.value.copy(status = "METAR Updated")
            } else {
                _uiState.value = _uiState.value.copy(status = "No airports within 50mi")
            }
        } catch (e: Exception) {
            Log.e("AltimeterViewModel", "Fetch failed", e)
            _uiState.value = _uiState.value.copy(status = "Fetch failed: ${e.message}")
        }
    }

    private fun updateAltimeterWithAirports(airports: List<AirportData>) {
        var totalWeight = 0.0
        var weightedSum = 0.0
        val finalAirports = airports.map {
            val weight = 1.0 / (it.distanceKm * it.distanceKm + 1.0)
            totalWeight += weight
            weightedSum += it.pressureHpa * weight
            it.copy(weight = weight)
        }

        val avgSeaPressure = if (totalWeight > 0) (weightedSum / totalWeight).toFloat() else 1013.25f
        val altitude = 44330 * (1.0 - (currentPressure / avgSeaPressure).toDouble().pow(1.0 / 5.255))

        _uiState.value = _uiState.value.copy(
            correctedAltitudeM = altitude.toInt(),
            airports = finalAirports,
            lastFetchTime = System.currentTimeMillis()
        )
    }

    fun toggleDetail() {
        _uiState.value = _uiState.value.copy(isDetailVisible = !_uiState.value.isDetailVisible)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            currentPressure = event.values[0]
            _uiState.value = _uiState.value.copy(rawPressureHpa = currentPressure.toInt())
            if (_uiState.value.airports.isNotEmpty()) {
                updateAltimeterWithAirports(_uiState.value.airports)
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onCleared() {
        super.onCleared()
        client.close()
        sensorManager.unregisterListener(this)
    }
}
