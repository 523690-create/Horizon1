package com.example.horizon2

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

data class AircraftData(
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val altitudeM: Float,
    val speedKts: Float,
    val heading: Float,
    val distanceKm: Float,
    val bearingDegrees: Float,
    val tailNumber: String = "",
    val aircraftType: String = "",
    val origin: String = "",
    val destination: String = "",
    val airlineName: String = "",
    val aircraftTypeName: String = "",
    val icao24: String = "",
    val lastSeen: Long = System.currentTimeMillis()
)

data class CelestialObject(
    val name: String,
    val azimuth: Float,      // 0-360 degrees, 0=North, 90=East
    val altitude: Float,      // -90 to 90 degrees, positive = above horizon
    val magnitude: Float = 0f, // for stars/planets
    val type: String = "star", // "star", "planet", "sun", "moon"
    val bayer: String = ""    // Bayer designation (Greek letter + constellation)
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
    val sensorDelay: Int = SensorManager.SENSOR_DELAY_UI,
    val overlayAlpha: Float = 0.8f,
    val lastMetarStatus: String = "",
    val planesDistanceValue: Float = 0.5f,
    val planesDistance: Float = 10f,
    val nearbyAircraft: List<AircraftData> = emptyList(),
    val lastAdbSource: String = "None",
    val lastAdbUpdateTime: String = "",
    val isVerbose: Boolean = false,
    val showGrounded: Boolean = true,
    val showConstellations: Boolean = false,
    val celestialObjects: List<CelestialObject> = emptyList(),
    val constellationLines: List<Pair<CelestialObject, CelestialObject>> = emptyList(),
    val constellationLabels: List<Pair<String, Pair<Float, Float>>> = emptyList() // Name to (AvgAz, AvgAlt)
)

class SensorViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val aircraftRepository = AircraftRepository()
    private val horizonsRepository = HorizonsRepository()
    private val prefs = application.getSharedPreferences("horizon2_prefs", Context.MODE_PRIVATE)

    // Metadata Cache for cross-referencing sources (ICAO24 -> Metadata)
    private data class AircraftMetadata(val r: String, val t: String, val orig: String, val dest: String)
    private val metadataCache = mutableMapOf<String, AircraftMetadata>()
    
    // Active aircraft master list (ICAO24 -> AircraftData) for merging sources
    private val activeAircraft = mutableMapOf<String, AircraftData>()

    // Constellation definitions (Bayer -> Bayer)
    private val constellations = mapOf(
        "Orion" to listOf(
            "α Orionis" to "γ Orionis", "γ Orionis" to "δ Orionis",
            "δ Orionis" to "ε Orionis", "ε Orionis" to "ζ Orionis",
            "ζ Orionis" to "α Orionis", "ζ Orionis" to "κ Orionis",
            "κ Orionis" to "β Orionis", "β Orionis" to "δ Orionis"
        ),
        "Ursa Major" to listOf(
            "α Ursae Majoris" to "β Ursae Majoris", "β Ursae Majoris" to "γ Ursae Majoris",
            "γ Ursae Majoris" to "δ Ursae Majoris", "δ Ursae Majoris" to "α Ursae Majoris",
            "δ Ursae Majoris" to "ε Ursae Majoris", "ε Ursae Majoris" to "ζ Ursae Majoris",
            "ζ Ursae Majoris" to "η Ursae Majoris"
        ),
        "Cassiopeia" to listOf(
            "β Cassiopeiae" to "α Cassiopeiae", "α Cassiopeiae" to "γ Cassiopeiae",
            "γ Cassiopeiae" to "δ Cassiopeiae", "δ Cassiopeiae" to "ε Cassiopeiae"
        ),
        "Leo" to listOf(
            "α Leonis" to "η Leonis", "η Leonis" to "γ Leonis", "γ Leonis" to "δ Leonis",
            "δ Leonis" to "β Leonis", "δ Leonis" to "θ Leonis", "θ Leonis" to "α Leonis"
        ),
        "Crux" to listOf(
            "γ Crucis" to "α Crucis", "β Crucis" to "δ Crucis"
        ),
        "Scorpius" to listOf(
            "β Scorpii" to "δ Scorpii", "δ Scorpii" to "π Scorpii", "π Scorpii" to "α Scorpii",
            "α Scorpii" to "σ Scorpii", "σ Scorpii" to "τ Scorpii", "τ Scorpii" to "ε Scorpii",
            "ε Scorpii" to "μ Scorpii", "μ Scorpii" to "ζ Scorpii", "ζ Scorpii" to "η Scorpii",
            "η Scorpii" to "θ Scorpii", "θ Scorpii" to "ι Scorpii", "ι Scorpii" to "κ Scorpii",
            "κ Scorpii" to "λ Scorpii", "λ Scorpii" to "ν Scorpii"
        )
    )

    // Current settings
    private var currentSensorDelay = prefs.getInt(KEY_SENSOR_DELAY, SensorManager.SENSOR_DELAY_UI)
    private var currentOverlayAlpha = prefs.getFloat(KEY_OVERLAY_ALPHA, 0.8f)

    private val _uiState = MutableStateFlow(
        SensorData(
            sensorDelay = currentSensorDelay, 
            overlayAlpha = currentOverlayAlpha,
            planesDistanceValue = prefs.getFloat(KEY_PLANES_DIST_VAL, 0.5f),
            planesDistance = 10f.pow(prefs.getFloat(KEY_PLANES_DIST_VAL, 0.5f) * log10(99f)),
            isVerbose = prefs.getBoolean(KEY_IS_VERBOSE, false),
            showGrounded = prefs.getBoolean(KEY_SHOW_GROUNDED, true),
            showConstellations = prefs.getBoolean(KEY_SHOW_CONSTELLATIONS, false)
        )
    )
    val uiState: StateFlow<SensorData> = _uiState.asStateFlow()

    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var lastTimestamp: Long = 0

    // Automatic calibration tracking
    private val accelHistory = mutableListOf<Triple<Long, FloatArray, Float>>() // timestamp, values, magnitude
    private var lastCalibrationTime: Long = 0
    private val STABILITY_THRESHOLD = 1.50f // Vector jitter threshold (m/s^2)
    private val CALIBRATION_SUSPENSION_MS = 5000L 
    private val CALIBRATION_WINDOW_MS = 2000L // 2 seconds steady time

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
        sensorManager.registerListener(this, accelerometer, currentSensorDelay)
        sensorManager.registerListener(this, magnetometer, currentSensorDelay)
        sensorManager.registerListener(this, gyroscope, currentSensorDelay)

        viewModelScope.launch {
            aircraftRepository.checkConnectivity()
            aircraftRepository.fetchEnrichmentData()
            // Re-enrich existing data once maps are loaded
            if (activeAircraft.isNotEmpty()) {
                val now = System.currentTimeMillis()
                activeAircraft.keys.toList().forEach { key ->
                    activeAircraft[key]?.let { ac ->
                        activeAircraft[key] = ac.copy(
                            airlineName = aircraftRepository.getAirlineName(ac.callsign) ?: "",
                            aircraftTypeName = aircraftRepository.getAircraftName(ac.aircraftType) ?: ""
                        )
                    }
                }
                updateUiAircraftList(_uiState.value.lastAdbSource, _uiState.value.lastAdbUpdateTime)
            }
        }
        
        startAircraftRefreshLoop()
        startCelestialRefreshLoop()
    }

    private fun startCelestialRefreshLoop() {
        viewModelScope.launch {
            while (true) {
                val hasLocation = lastLat != 0.0 || lastLon != 0.0
                refreshCelestialData()
                if (!hasLocation) {
                    delay(5000L) // Retry every 5s if location is missing
                } else {
                    delay(10 * 60 * 1000L) // 10 minutes
                }
            }
        }
    }

    private suspend fun refreshCelestialData() {
        if (lastLat == 0.0 && lastLon == 0.0) {
            android.util.Log.w("SensorVM", "Skipping celestial refresh: No location")
            return
        }

        android.util.Log.d("SensorVM", "Refreshing celestial data for $lastLat, $lastLon")
        
        // 1. Fetch Planets from NASA JPL Horizons
        val planets = try {
            horizonsRepository.fetchPlanetPositions(lastLat, lastLon)
        } catch (e: Exception) {
            android.util.Log.e("SensorVM", "Planet fetch error: ${e.message}")
            emptyList()
        }
        android.util.Log.d("SensorVM", "Fetched ${planets.size} planets")

        // 2. Load and Calculate Stars from R.array.star_data
        val stars = mutableListOf<CelestialObject>()
        try {
            val starArray = getApplication<Application>().resources.getStringArray(R.array.star_data)
            android.util.Log.d("SensorVM", "Processing ${starArray.size} stars from resource")
            val jd = CelestialCalculator.getJulianDay(System.currentTimeMillis())
            
            starArray.forEach { entry ->
                val parts = entry.split(";")
                if (parts.size >= 5) {
                    val name = parts[0]
                    val bayer = parts[1]
                    val mag = parts[2].toFloatOrNull() ?: 2.0f
                    val ra = parts[3].toDoubleOrNull() ?: 0.0
                    val dec = parts[4].toDoubleOrNull() ?: 0.0
                    
                    val azAlt = CelestialCalculator.raDecToAzAlt(ra, dec, lastLat, lastLon, jd)
                    if (azAlt.second > -10.0f) {
                        stars.add(CelestialObject(name, azAlt.first, azAlt.second, mag, "star", bayer))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SensorVM", "Error loading stars: ${e.message}")
        }
        android.util.Log.d("SensorVM", "Calculated ${stars.size} visible stars")

        // 3. Add Sun and Moon (using internal calculator for efficiency)
        val jd = CelestialCalculator.getJulianDay(System.currentTimeMillis())
        val sun = CelestialCalculator.calculateSunPosition(lastLat, lastLon, jd)
        val moon = CelestialCalculator.calculateMoonPosition(lastLat, lastLon, jd)

        val allCelestial = (planets + stars + sun + moon).sortedBy { it.magnitude }
        
        // 4. Calculate Constellation Lines and Labels if enabled
        val lines = mutableListOf<Pair<CelestialObject, CelestialObject>>()
        val labels = mutableListOf<Pair<String, Pair<Float, Float>>>()
        
        constellations.forEach { (name, connections) ->
            val constellationStars = mutableListOf<CelestialObject>()
            connections.forEach { (b1, b2) ->
                val s1 = stars.find { it.bayer == b1 }
                val s2 = stars.find { it.bayer == b2 }
                if (s1 != null && s2 != null) {
                    lines.add(s1 to s2)
                    if (!constellationStars.contains(s1)) constellationStars.add(s1)
                    if (!constellationStars.contains(s2)) constellationStars.add(s2)
                }
            }
            if (constellationStars.isNotEmpty()) {
                val avgAz = constellationStars.map { it.azimuth }.average().toFloat()
                val avgAlt = constellationStars.map { it.altitude }.average().toFloat()
                labels.add(name to (avgAz to avgAlt))
            }
        }

        _uiState.value = _uiState.value.copy(
            celestialObjects = allCelestial,
            constellationLines = lines,
            constellationLabels = labels
        )
        android.util.Log.d("CelestialRefresh", "Refreshed ${allCelestial.size} celestial objects, ${lines.size} lines")
    }

    private fun startAircraftRefreshLoop() {
        viewModelScope.launch {
            val sources = listOf("Airplanes.Live", "ADS-B Exchange", "OpenSky")
            var sourceIndex = 0
            
            while (true) {
                refreshAircraftData(sources[sourceIndex])
                sourceIndex = (sourceIndex + 1) % sources.size
                // Rotate every 1 minute for a more complete merged list
                delay(60 * 1000L) 
            }
        }
    }

    private suspend fun refreshAircraftData(source: String) {
        val fetchRadius = 100f
        if (lastLat == 0.0 && lastLon == 0.0) return

        val rawList = when (source) {
            "OpenSky" -> aircraftRepository.fetchOpenSky(lastLat, lastLon, fetchRadius)
            "Airplanes.Live" -> aircraftRepository.fetchAirplanesLive(lastLat, lastLon, fetchRadius)
            "ADS-B Exchange" -> aircraftRepository.fetchAdsbExchange(lastLat, lastLon, fetchRadius)
            else -> emptyList()
        }
        
        val currentTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val now = System.currentTimeMillis()

        // 1. DEDUPLICATE incoming data and update metadata cache
        // Normalize key to avoid duplicates (strip spaces from callsign)
        val deduplicated = rawList.distinctBy { it.icao24.ifEmpty { it.callsign.replace(" ", "") } }
        deduplicated.forEach { ac ->
            if (ac.icao24.isNotEmpty()) {
                val existing = metadataCache[ac.icao24]
                metadataCache[ac.icao24] = AircraftMetadata(
                    ac.tailNumber.ifEmpty { existing?.r ?: "" },
                    ac.aircraftType.ifEmpty { existing?.t ?: "" },
                    ac.origin.ifEmpty { existing?.orig ?: "" },
                    ac.destination.ifEmpty { existing?.dest ?: "" }
                )
            }
        }

        // 2. MERGE into active aircraft map
        deduplicated.forEach { ac ->
            val key = ac.icao24.ifEmpty { ac.callsign.replace(" ", "") }
            val meta = metadataCache[ac.icao24]
            
            val enriched = ac.copy(
                tailNumber = ac.tailNumber.ifEmpty { meta?.r ?: "" },
                aircraftType = ac.aircraftType.ifEmpty { meta?.t ?: "" },
                origin = ac.origin.ifEmpty { meta?.orig ?: "" },
                destination = ac.destination.ifEmpty { meta?.dest ?: "" },
                airlineName = aircraftRepository.getAirlineName(ac.callsign) ?: "",
                aircraftTypeName = aircraftRepository.getAircraftName(ac.aircraftType.ifEmpty { meta?.t ?: "" }) ?: "",
                lastSeen = now
            )
            activeAircraft[key] = enriched

            // 3. ASYNC ROUTE SUPPLEMENT for displayable aircraft missing routes
            if (enriched.origin.isEmpty() && enriched.icao24.isNotEmpty()) {
                viewModelScope.launch {
                    val routeStr = aircraftRepository.fetchRoute(enriched.icao24)
                    if (routeStr != null) {
                        val parts = routeStr.split("-")
                        if (parts.size >= 2) {
                            val o = parts[0].trim()
                            val d = parts[1].trim()
                            // Update cache
                            val m = metadataCache[enriched.icao24]
                            metadataCache[enriched.icao24] = AircraftMetadata(m?.r ?: "", m?.t ?: "", o, d)
                            // Update active map and UI immediately
                            val current = activeAircraft[enriched.icao24]
                            if (current != null) {
                                val updated = current.copy(origin = o, destination = d)
                                activeAircraft[enriched.icao24] = updated
                                updateUiAircraftList(source, currentTime)
                            }
                        }
                    }
                }
            }
        }

        // 4. CLEANUP: Remove aircraft not seen for > 10 minutes
        val expirationTime = 10 * 60 * 1000L
        activeAircraft.entries.removeIf { now - it.value.lastSeen > expirationTime }

        updateUiAircraftList(source, currentTime)
    }

    private fun updateUiAircraftList(source: String, time: String) {
        _uiState.value = _uiState.value.copy(
            lastAdbSource = source,
            nearbyAircraft = activeAircraft.values.toList().sortedBy { it.distanceKm },
            lastAdbUpdateTime = time
        )
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
        if (speed > 1.0f && bearingAccuracy <= 15.0f) { // Relaxed from 2.0f and 4.0f
            val gyroOrientation = getOrientationFromMatrix(gyroOnlyMatrix)
            
            // Offset = GpsBearing - CurrentGyroAzimuth
            val offset = (bearing - gyroOrientation.azimuth + 540) % 360 - 180
            
            // Apply a simple low-pass filter if we already have an offset
            gpsNorthOffset = gpsNorthOffset?.let { (0.90f * it) + (0.10f * offset) } ?: offset
            
            _uiState.value = _uiState.value.copy(
                isGpsCalibrated = true,
                gpsCalibrationTime = System.currentTimeMillis()
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
                overlayAlpha = currentOverlayAlpha,
                isManualCalibrated = true,
                manualCalibrationTime = System.currentTimeMillis()
            )
        }
    }

    fun cancelManualCalibration() {
        _uiState.value = _uiState.value.copy(overlayAlpha = currentOverlayAlpha)
    }

    fun setDisplayRotation(rotation: Int) {
        displayRotation = rotation
    }

    fun updateMetarStatus(status: String) {
        _uiState.value = _uiState.value.copy(lastMetarStatus = status)
    }

    fun updateSensorDelay(delay: Int) {
        currentSensorDelay = delay
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, accelerometer, delay)
        sensorManager.registerListener(this, magnetometer, delay)
        sensorManager.registerListener(this, gyroscope, delay)
        _uiState.value = _uiState.value.copy(sensorDelay = delay)
        prefs.edit().putInt(KEY_SENSOR_DELAY, delay).apply()
    }

    fun updateOverlayAlpha(alpha: Float) {
        currentOverlayAlpha = alpha
        _uiState.value = _uiState.value.copy(overlayAlpha = alpha)
        prefs.edit().putFloat(KEY_OVERLAY_ALPHA, alpha).apply()
    }

    fun updatePlanesDistance(value: Float) {
        // Calculate logarithmic distance: 10^(value * log10(99.0)) -> 10^0 = 1, 10^log10(99) = 99
        val maxDist = 99f
        val distance = 10f.pow(value * log10(maxDist))
        _uiState.value = _uiState.value.copy(
            planesDistanceValue = value,
            planesDistance = distance
        )
        prefs.edit().putFloat(KEY_PLANES_DIST_VAL, value).apply()
    }

    fun toggleVerbose() {
        val newState = !_uiState.value.isVerbose
        _uiState.value = _uiState.value.copy(isVerbose = newState)
        prefs.edit().putBoolean(KEY_IS_VERBOSE, newState).apply()
    }

    fun toggleGrounded() {
        val newState = !_uiState.value.showGrounded
        _uiState.value = _uiState.value.copy(showGrounded = newState)
        prefs.edit().putBoolean(KEY_SHOW_GROUNDED, newState).apply()
    }

    fun toggleConstellations() {
        val newState = !_uiState.value.showConstellations
        _uiState.value = _uiState.value.copy(showConstellations = newState)
        prefs.edit().putBoolean(KEY_SHOW_CONSTELLATIONS, newState).apply()
    }

    fun triggerManualAircraftRefresh() {
        viewModelScope.launch {
            val currentSource = _uiState.value.lastAdbSource
            val sourceToUse = if (currentSource == "None" || currentSource.isEmpty()) "OpenSky" else currentSource
            refreshAircraftData(sourceToUse)
        }
    }

    fun pauseSensors() {
        sensorManager.unregisterListener(this)
    }

    fun resumeSensors() {
        accelerometer?.let { sensorManager.registerListener(this, it, currentSensorDelay) }
        magnetometer?.let { sensorManager.registerListener(this, it, currentSensorDelay) }
        gyroscope?.let { sensorManager.registerListener(this, it, currentSensorDelay) }
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

        // 3. Precise Stability check (2 seconds window)
        if (accelHistory.isNotEmpty() && (currentTime - accelHistory.first().first) >= CALIBRATION_WINDOW_MS) {
            // Calculate average vector
            var avgX = 0f; var avgY = 0f; var avgZ = 0f
            var avgMag = 0f
            for (sample in accelHistory) {
                avgX += sample.second[0]; avgY += sample.second[1]; avgZ += sample.second[2]
                avgMag += sample.third
            }
            avgX /= accelHistory.size; avgY /= accelHistory.size; avgZ /= accelHistory.size
            avgMag /= accelHistory.size

            // Scalar check: 1G (9.81 m/s^2) +/- 0.1G (0.981 m/s^2)
            val g = 9.81f
            val gThreshold = 0.0981f //do not change
            if (abs(avgMag - g) > gThreshold) {
                accelHistory.clear()
                return
            }

            // Vector check: +/- 2.5 degrees stability
            // Angular difference between each sample and the average must be < 2.5 deg
            val stabilityThresholdDeg = 1.5f //do not change
            val cosThreshold = cos(Math.toRadians(stabilityThresholdDeg.toDouble())).toFloat()
            
            val avgNorm = sqrt(avgX*avgX + avgY*avgY + avgZ*avgZ)
            
            for (sample in accelHistory) {
                val s = sample.second
                val sNorm = sqrt(s[0]*s[0] + s[1]*s[1] + s[2]*s[2])
                if (sNorm == 0f || avgNorm == 0f) continue
                
                // Dot product / (norm1 * norm2) = cos(theta)
                val dot = (s[0]*avgX + s[1]*avgY + s[2]*avgZ) / (sNorm * avgNorm)
                if (dot < cosThreshold) {
                    accelHistory.clear()
                    return
                }
            }

            // If all checks pass, "snap" the orientation (level the horizon)
            // YAW-INVARIANT CALIBRATION: 
            // 1. Extract absolute "Up" from fusedMatrix (accelerometer-based)
            // 2. Extract current "North" estimate from gyroOnlyMatrix
            // 3. Reconstruct coordinate system to align with Up but preserve North heading
            
            val up = floatArrayOf(fusedMatrix[6], fusedMatrix[7], fusedMatrix[8])
            val currentNorth = floatArrayOf(gyroOnlyMatrix[3], gyroOnlyMatrix[4], gyroOnlyMatrix[5])
            
            // East = North x Up
            val eastX = currentNorth[1] * up[2] - currentNorth[2] * up[1]
            val eastY = currentNorth[2] * up[0] - currentNorth[0] * up[2]
            val eastZ = currentNorth[0] * up[1] - currentNorth[1] * up[0]
            
            val eastMag = sqrt(eastX*eastX + eastY*eastY + eastZ*eastZ)
            if (eastMag > 0.001f) {
                val eX = eastX / eastMag; val eY = eastY / eastMag; val eZ = eastZ / eastMag
                
                // New North = Up x East
                val nX = up[1] * eZ - up[2] * eY
                val nY = up[2] * eX - up[0] * eZ
                val nZ = up[0] * eY - up[1] * eX
                
                // Update gyroOnlyMatrix with new East, North, and Up
                gyroOnlyMatrix[0] = eX; gyroOnlyMatrix[1] = eY; gyroOnlyMatrix[2] = eZ
                gyroOnlyMatrix[3] = nX; gyroOnlyMatrix[4] = nY; gyroOnlyMatrix[5] = nZ
                gyroOnlyMatrix[6] = up[0]; gyroOnlyMatrix[7] = up[1]; gyroOnlyMatrix[8] = up[2]
                
                normalizeMatrix(gyroOnlyMatrix)
            } else {
                // Edge case: North and Up are parallel. Fall back to simple clone if reconstruction fails.
                gyroOnlyMatrix = fusedMatrix.clone()
            }

            lastCalibrationTime = currentTime
            _uiState.value = _uiState.value.copy(
                calibrationState = CalibrationState.CALIBRATED,
                hasBeenCalibrated = true
            )
            accelHistory.clear()
            android.util.Log.d("AutoCalibrate", "Yaw-invariant orientation snapped at scalar ${avgMag}m/s^2")
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
        
        // 1. Detect Flat orientation: Trigger at 15 degrees from horizontal
        // cos(15)/sin(15) ~ 3.73
        val isFlat = abs(lastRawAccel[2]) > 3.73f * (abs(lastRawAccel[0]) + abs(lastRawAccel[1]))

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

            val currentTrueHeading = if (bestOffset != null) {
                (filteredTrueAzimuth + bestOffset + 360) % 360
            } else {
                filteredTrueAzimuth
            }

            // Bubble offsets: Projected World Z onto Device XY
            val gBX = Math.toDegrees(asin(fusedMatrix[6].toDouble())).toFloat()
            val gBY = Math.toDegrees(asin(fusedMatrix[7].toDouble())).toFloat()
            val currentGyroMatrix = if (isCalibrated) gyroOnlyMatrix else fusedMatrix
            val wBX = Math.toDegrees(asin(currentGyroMatrix[6].toDouble())).toFloat()
            val wBY = Math.toDegrees(asin(currentGyroMatrix[7].toDouble())).toFloat()

            _uiState.value = _uiState.value.copy(
                pitch = fusedOrientation.pitch, roll = fusedOrientation.roll, heading = fusedOrientation.azimuth, headingString = getHeadingString(fusedOrientation.azimuth),
                truePitch = filteredTruePitch, trueRoll = filteredTrueRoll, trueFullPitch = filteredTrueFullPitch,
                trueHeading = currentTrueHeading,
                trueHeadingString = getHeadingString(currentTrueHeading),
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
        val wasZero = lastLat == 0.0 && lastLon == 0.0
        lastLat = lat; lastLon = lon
        if (wasZero) {
            viewModelScope.launch { refreshCelestialData() }
        }
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

    companion object {
        private const val KEY_PLANES_DIST_VAL = "planes_distance_value"
        private const val KEY_IS_VERBOSE = "is_verbose"
        private const val KEY_SHOW_GROUNDED = "show_grounded"
        private const val KEY_SHOW_CONSTELLATIONS = "show_constellations"
        private const val KEY_SENSOR_DELAY = "sensor_delay"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
    }
}

// Celestial calculation utilities
object CelestialCalculator {

    // Bright stars of magnitude 2.0 or brighter (subset of commonly visible)
    private val brightStars = listOf(
        // Using a 4th parameter for Magnitude (previously a Triple with 4 args which caused error)
        Triple("Sirius", 101.29, -16.72) to -1.46f,
        Triple("Canopus", 95.99, -52.69) to -0.74f,
        Triple("Arcturus", 213.91, 19.18) to -0.04f,
        Triple("Vega", 279.23, 38.78) to 0.03f,
        Triple("Capella", 79.17, 45.99) to 0.08f,
        Triple("Rigel", 78.63, -8.20) to 0.13f,
        Triple("Procyon", 114.83, 5.23) to 0.38f,
        Triple("Altair", 297.69, 8.87) to 0.76f,
        Triple("Betelgeuse", 88.79, 7.40) to 0.50f,
        Triple("Aldebaran", 68.98, 16.50) to 0.87f,
        Triple("Spica", 201.30, -11.16) to 0.98f,
        Triple("Antares", 247.35, -26.43) to 0.92f,
        Triple("Pollux", 131.89, 28.03) to 1.16f,
        Triple("Castor", 116.33, 31.89) to 1.59f,
        Triple("Deneb", 310.36, 45.28) to 1.25f,
        Triple("Polaris", 37.95, 89.26) to 2.02f,
        Triple("Fomalhaut", 344.41, -29.62) to 1.17f,
        Triple("Adhara", 104.65, -28.97) to 1.50f,
        Triple("Regulus", 152.09, 11.97) to 1.36f,
        Triple("Albireo", 292.65, 27.95) to 3.08f
    )

    // Visible planets (simplified - assumes they're roughly in ecliptic)
    // In reality, you'd fetch ephemeris data, but these are approximate
    private fun getPlanetPositions(jd: Double, observerLat: Double, observerLon: Double): List<CelestialObject> {
        val now = System.currentTimeMillis()
        val planets = mutableListOf<CelestialObject>()

        // Approximate planet RA/Dec (simplified, not accurate for precise astronomy)
        // These should be fetched from ephemeris data in a real implementation
        val planetData = listOf(
            Pair("Venus", Pair(180.0, 10.0)),    // Approximate RA, Dec
            Pair("Mars", Pair(45.0, 5.0)),
            Pair("Jupiter", Pair(200.0, -15.0)),
            Pair("Saturn", Pair(300.0, 20.0))
        )

        for ((name, raDec) in planetData) {
            val (ra, dec) = raDec
            val azAlt = raDecToAzAlt(ra, dec, observerLat, observerLon, jd)
            if (azAlt.second > -10.0f) { // Only show if above -10 degrees (some light still visible)
                planets.add(CelestialObject(name, azAlt.first, azAlt.second, 0f, "planet"))
            }
        }

        return planets
    }

    fun calculateCelestialObjects(observerLat: Double, observerLon: Double): List<CelestialObject> {
        val jd = getJulianDay(System.currentTimeMillis())
        val objects = mutableListOf<CelestialObject>()

        // Add bright stars
        for (star in brightStars) {
            val (name, ra, dec) = star.first
            val mag = star.second
            val (az, alt) = raDecToAzAlt(ra, dec, observerLat, observerLon, jd)
            if (alt > -2.0f) { // Show stars down to -2 degrees (atmospheric refraction)
                objects.add(CelestialObject(name, az, alt, mag, "star"))
            }
        }

        // Add planets
        objects.addAll(getPlanetPositions(jd, observerLat, observerLon))

        // Add Sun and Moon
        val sun = calculateSunPosition(observerLat, observerLon, jd)
        objects.add(sun)

        val moon = calculateMoonPosition(observerLat, observerLon, jd)
        objects.add(moon)

        return objects.sortedBy { it.azimuth }
    }

    fun raDecToAzAlt(ra: Double, dec: Double, lat: Double, lon: Double, jd: Double): Pair<Float, Float> {
        val lat_rad = Math.toRadians(lat)
        val dec_rad = Math.toRadians(dec)

        // Get local sidereal time
        val lst = getLocalSiderealTime(lon, jd)
        val ha = lst - ra // Hour angle
        val ha_rad = Math.toRadians(ha)

        // Convert to horizontal coordinates
        val sin_alt = sin(dec_rad) * sin(lat_rad) + cos(dec_rad) * cos(lat_rad) * cos(ha_rad)
        val alt = Math.toDegrees(asin(sin_alt)).toFloat()

        val y = sin(ha_rad)
        val x = cos(ha_rad) * sin(lat_rad) - tan(dec_rad) * cos(lat_rad)
        
        // Atan2(y,x) gives azimuth measured Westward from South? 
        // Let's use a more standard one for North=0, East=90
        val az_west = (Math.toDegrees(atan2(y, x)).toFloat() + 360) % 360
        val azimuth = (360 - az_west) % 360 // Convert to Eastward

        return Pair(azimuth, alt)
    }

  fun calculateSunPosition(lat: Double, lon: Double, jd: Double): CelestialObject {
        // Simplified Sun position calculation
        val n = jd - 2451545.0  // Days since J2000
        val L = (280.46646 + 0.8697 * n) % 360
        val g_rad = Math.toRadians((357.52911 + 0.9856 * n) % 360)

        val sunLon = (L + 1.914 * sin(g_rad) + 0.02 * sin(2 * g_rad)) % 360
        val sunLat = 0.0

        val ra = Math.toDegrees(atan2(sin(Math.toRadians(sunLon)) * cos(Math.toRadians(23.44)), cos(Math.toRadians(sunLon)))).toDouble() % 360
        val dec = Math.toDegrees(asin(sin(Math.toRadians(23.44)) * sin(Math.toRadians(sunLon)))).toDouble()

        val (az, alt) = raDecToAzAlt(ra, dec, lat, lon, jd)
        return CelestialObject("Sun", az, alt, -26.7f, "sun")
    }

     fun calculateMoonPosition(lat: Double, lon: Double, jd: Double): CelestialObject {
        // Simplified Moon position calculation
        val n = jd - 2451545.0
        val l = (218.3165 + 13.17639 * n) % 360
        val m = (134.9645 + 13.06499 * n) % 360
        val f = (93.2721 + 13.22935 * n) % 360

        val l_rad = Math.toRadians(l)
        val m_rad = Math.toRadians(m)
        val f_rad = Math.toRadians(f)

        val moonLon = (l + 6.29 * sin(m_rad) + 2.1 * sin(2 * f_rad)) % 360
        val moonLat = (5.13 * sin(f_rad) + 2.2 * sin(m_rad - f_rad)) % 360

        val ra = Math.toDegrees(atan2(sin(Math.toRadians(moonLon)) * cos(Math.toRadians(5.16)), cos(Math.toRadians(moonLon)))).toDouble() % 360
        val dec = Math.toDegrees(asin(sin(Math.toRadians(5.16)) * sin(Math.toRadians(moonLon)))).toDouble()

        val (az, alt) = raDecToAzAlt(ra, dec, lat, lon, jd)
        return CelestialObject("Moon", az, alt, -12.6f, "moon")
    }

     fun getJulianDay(timeMs: Long): Double {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timeMs

        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val second = calendar.get(java.util.Calendar.SECOND)

        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3

        val jd = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045.0
        val jd_frac = (hour + minute / 60.0 + second / 3600.0) / 24.0

        return jd + jd_frac - 0.5
    }

    private fun getLocalSiderealTime(lon: Double, jd: Double): Double {
        val jd2000 = 2451545.0
        val days = jd - jd2000

        val gmst = (18.697374558 + 24.06570982441908 * days) % 24.0
        val lst = (gmst * 15.0 + lon) % 360.0

        return if (lst < 0) lst + 360.0 else lst
    }
}




