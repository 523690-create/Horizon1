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
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

@Serializable
data class NoaaMetar(
    val icaoId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val elev: Int? = null,
    val altim: Float? = null,
    val name: String? = null,
    val reportTime: String? = null
)

data class AirportData(
    val code: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double,
    val pressureHpa: Float,
    val distanceKm: Double = 0.0,
    val weight: Double = 0.0,
    val reportTime: String = ""
)

data class AltimeterData(
    val rawPressureHpa: Float = 1013.25f,
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
    private var lastPressureUpdateTime = 0L

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                isLenient = true
            })
        }
    }

    init {
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(status = "Loading Airport DB...")
            AirportDb.load(client)
            startAirportUpdateLoop()
        }
    }

    private fun startAirportUpdateLoop() {
        viewModelScope.launch {
            while (true) {
                lastLocation?.let { (lat, lon) ->
                    if (AirportDb.isLoaded) fetchMetarData(lat, lon)
                }
                delay(600000L) // 10 minutes
            }
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        
        val isFirstLocation = lastLocation == null
        lastLocation = lat to lon
        Log.d("AltimeterViewModel", "Location updated: $lat, $lon. isFirst: $isFirstLocation")
        if (isFirstLocation && AirportDb.isLoaded) {
            viewModelScope.launch { fetchMetarData(lat, lon) }
        }
    }

    private suspend fun fetchMetarData(lat: Double, lon: Double) {
        try {
            _uiState.value = _uiState.value.copy(status = "Fetching NOAA...")
            
            // 1. Get 5 closest ICAOs from local DB
            val closest = AirportDb.getClosestAirports(lat, lon, 5)
            val ids = closest.joinToString(",") { it.icao }
            Log.d("AltimeterViewModel", "Target ICAOs: $ids")
            
            // 2. Fetch specific METARs from NOAA
            val url = "https://aviationweather.gov/api/data/metar?ids=$ids&format=json&decoded=true&hours=6"
            Log.d("AltimeterViewModel", "URL: $url")
            
            val httpResponse: HttpResponse = client.get(url) {
                header("User-Agent", "Mozilla/5.0 (Android; Horizon1 Altimeter App)")
                header("Accept", "application/json")
            }
            
            val responseString = httpResponse.bodyAsText()
            Log.d("AltimeterViewModel", "Status: ${httpResponse.status}, Body length: ${responseString.length}")
            
            // Log raw response snippet to verify format
            Log.d("AltimeterViewModel", "Raw JSON Snippet: ${responseString.take(500)}")

            if (httpResponse.status == HttpStatusCode.NoContent || responseString.trim().isEmpty()) {
                _uiState.value = _uiState.value.copy(status = "No data for targets")
                return
            }

            val noaaList: List<NoaaMetar> = Json { ignoreUnknownKeys = true }.decodeFromString(responseString)
            Log.d("AltimeterViewModel", "Total reports received: ${noaaList.size}")

            // 3. Group by ICAO and take the latest report for each unique airport
            val fetchedAirports = noaaList.filter { it.icaoId != null && it.altim != null && it.lat != null && it.lon != null }
                .groupBy { it.icaoId }
                .map { (icao, reports) ->
                    // NOAA JSON altim is already in hPa (e.g. 1012.6)
                    val report = reports[0] 
                    val pressureHpa = report.altim!!
                    val dist = calculateDistance(lat, lon, report.lat!!, report.lon!!)
                    AirportData(
                        code = icao!!,
                        name = report.name ?: "Unknown",
                        latitude = report.lat,
                        longitude = report.lon,
                        elevationM = report.elev?.toDouble() ?: 0.0,
                        pressureHpa = pressureHpa,
                        distanceKm = dist,
                        reportTime = report.reportTime?.substringAfter("T")?.substringBefore(".") ?: ""
                    )
                }.sortedBy { it.distanceKm }.take(5)

            if (fetchedAirports.isNotEmpty()) {
                updateAltimeterWithAirports(fetchedAirports)
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                _uiState.value = _uiState.value.copy(status = "METAR Updated at $time")
            } else {
                _uiState.value = _uiState.value.copy(status = "No data for targets")
            }
        } catch (e: Exception) {
            Log.e("AltimeterViewModel", "NOAA Fetch failed", e)
            _uiState.value = _uiState.value.copy(status = "NOAA Error: ${e.message}")
        }
    }

    private fun updateAltimeterWithAirports(airports: List<AirportData>) {
        var totalWeight = 0.0
        var weightedSum = 0.0
        val top5 = airports.take(5)
        val finalAirports = top5.map {
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
            val now = System.currentTimeMillis()
            if (now - lastPressureUpdateTime >= 1000L) {
                currentPressure = event.values[0]
                _uiState.value = _uiState.value.copy(rawPressureHpa = currentPressure)
                if (_uiState.value.airports.isNotEmpty()) {
                    updateAltimeterWithAirports(_uiState.value.airports)
                }
                lastPressureUpdateTime = now
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
