package com.example.horizon2

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.*

@Serializable
data class OpenSkyResponse(
    val time: Long,
    val states: List<JsonArray>? = null
)

class AircraftRepository {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
        }
    }

    // Enrichment Data Maps
    private val airlineMap = mutableMapOf<String, String>() // ICAO -> Name
    private val aircraftMap = mutableMapOf<String, String>() // ICAO Type -> Name
    // For routes, we'll store a simple string for now if we can find a match
    private val routeMap = mutableMapOf<String, String>() // Airline+Dest? This is tricky without a unique key

    init {
        // Pre-populate with some common ones in case download fails or info is missing
        airlineMap.putAll(mapOf(
            "AAL" to "American Airlines", "BAW" to "British Airways", "DAL" to "Delta Air Lines",
            "DLH" to "Lufthansa", "FDX" to "FedEx", "JBU" to "JetBlue Airways",
            "KLM" to "KLM Royal Dutch", "SWA" to "Southwest Airlines", "UAL" to "United Airlines",
            "UPS" to "United Parcel Service", "AFR" to "Air France", "RYR" to "Ryanair",
            "EZY" to "easyJet", "WZZ" to "Wizz Air", "THY" to "Turkish Airlines",
            "SIA" to "Singapore Airlines", "QFA" to "Qantas", "UAE" to "Emirates",
            "ETD" to "Etihad Airways", "ACA" to "Air Canada", "ASA" to "Alaska Airlines",
            "NKS" to "Spirit Airlines", "FFT" to "Frontier Airlines", "SKW" to "SkyWest Airlines",
            "ENY" to "Envoy Air", "PDT" to "Piedmont Airlines", "EDV" to "Endeavor Air",
            "ASH" to "Mesa Airlines", "GJS" to "GoJet Airlines", "RPA" to "Republic Airways",
            "CPZ" to "Compass Airlines", "QXE" to "Horizon Air", "VOO" to "Volaris",
            "AMX" to "Aeroméxico", "AZA" to "Alitalia", "ANA" to "All Nippon Airways",
            "CAL" to "China Airlines", "CSA" to "Czech Airlines", "FIN" to "Finnair",
            "HAL" to "Hawaiian Airlines", "JAL" to "Japan Airlines", "KAL" to "Korean Air"
        ))
        aircraftMap.putAll(mapOf(
            "A320" to "Airbus A320", "A321" to "Airbus A321", "A333" to "Airbus A330-300",
            "A359" to "Airbus A350-900", "B737" to "Boeing 737", "B738" to "Boeing 737-800",
            "B739" to "Boeing 737-900", "B772" to "Boeing 777-200", "B77W" to "Boeing 777-300ER",
            "B788" to "Boeing 787-8", "B789" to "Boeing 787-9", "C172" to "Cessna 172",
            "CRJ7" to "Bombardier CRJ-700", "CRJ9" to "Bombardier CRJ-900", "E175" to "Embraer 175",
            "E190" to "Embraer 190", "E170" to "Embraer 170", "E145" to "Embraer ERJ-145",
            "GLF4" to "Gulfstream IV", "GLF5" to "Gulfstream V", "GLF6" to "Gulfstream VI",
            "G550" to "Gulfstream G550", "G650" to "Gulfstream G650", "BCS3" to "Airbus A220-300",
            "CL30" to "Challenger 300", "CL35" to "Challenger 350", "CL60" to "Challenger 600",
            "GLEX" to "Global Express", "PC12" to "Pilatus PC-12", "PC24" to "Pilatus PC-24",
            "C525" to "Cessna CitationJet", "C560" to "Cessna Citation V"
        ))
    }

    suspend fun checkConnectivity(): Boolean {
        return try {
            val response = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat")
            android.util.Log.d("AircraftRepo", "Connectivity check status: ${response.status}")
            true
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "Connectivity check failed: ${e.message}")
            false
        }
    }

    suspend fun fetchEnrichmentData() {
        android.util.Log.d("AircraftRepo", "Starting data enrichment download...")
        
        // Robust CSV Parser Regex (handles commas inside quotes)
        val csvRegex = Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")

        // 1. Airlines (OpenFlights)
        try {
            val airlines = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat").bodyAsText()
            airlines.lineSequence().forEach { line ->
                val parts = line.split(csvRegex)
                if (parts.size >= 5) {
                    val name = parts[1].replace("\"", "").trim()
                    val icao = parts[4].replace("\"", "").trim()
                    // Filter out \N (null) and invalid entries
                    if (icao.length == 3 && icao != "\\N") {
                        airlineMap[icao] = name
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${airlineMap.size} airlines")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Airlines fetch failed: ${e.message}") }

        // 2. Aircraft (OpenFlights Planes)
        try {
            val planes = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/planes.dat").bodyAsText()
            planes.lineSequence().forEach { line ->
                val parts = line.split(csvRegex)
                if (parts.size >= 3) {
                    val name = parts[0].replace("\"", "").trim()
                    val icao = parts[2].replace("\"", "").trim()
                    if (icao.isNotEmpty() && icao != "\\N") {
                        aircraftMap[icao] = name
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${aircraftMap.size} aircraft types from OpenFlights")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Planes fetch failed: ${e.message}") }

        // 3. Aircraft (Slumbering001 Comprehensive)
        try {
            val icaoCodes = client.get("https://raw.githubusercontent.com/Slumbering001/Aircraft-Type-Codes/master/icao_type_codes.csv").bodyAsText()
            icaoCodes.lineSequence().forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 3) {
                    val icao = parts[0].replace("\"", "").trim()
                    val model = parts[1].replace("\"", "").trim()
                    val manufacturer = parts[2].replace("\"", "").trim()
                    if (icao.isNotEmpty() && icao != "Designator") {
                        aircraftMap[icao] = "$manufacturer $model"
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${aircraftMap.size} aircraft types after Slumbering001")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "ICAO Type Codes fetch failed: ${e.message}") }

        // 4. Aircraft (OpenTravelData fallback)
        try {
            val aircraft = client.get("https://raw.githubusercontent.com/opentraveldata/opentraveldata/master/opentraveldata/optd_aircraft.csv").bodyAsText()
            aircraft.lineSequence().forEach { line ->
                // Format: iata_type^icao_type^name^category
                val parts = line.split("^")
                if (parts.size >= 3) {
                    val icao = parts[1].trim()
                    val name = parts[2].trim()
                    if (icao.isNotEmpty() && name.isNotEmpty() && icao != "icao_type") {
                        if (!aircraftMap.containsKey(icao)) {
                            aircraftMap[icao] = name
                        }
                    }
                }
            }
            android.util.Log.d("AircraftRepo", "Total aircraft types loaded: ${aircraftMap.size}")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Aircraft CSV fetch failed: ${e.message}") }
    }

    fun getAirlineName(callsign: String): String? {
        val trimmed = callsign.trim().uppercase()
        if (trimmed.length < 3) return null
        
        // Try 3-letter ICAO first
        val icao3 = trimmed.substring(0, 3)
        airlineMap[icao3]?.let { return it }
        
        // Some regional/smaller ones might use different prefixes, but ICAO is standard for callsigns
        return null
    }

    fun getAircraftName(type: String): String? {
        if (type.isEmpty()) return null
        return aircraftMap[type.uppercase().trim()]
    }

    suspend fun fetchOpenSky(lat: Double, lon: Double, radiusKm: Float): List<AircraftData> {
        val deltaLat = radiusKm / 111.0
        val deltaLon = radiusKm / (111.0 * cos(Math.toRadians(lat)))
        
        val url = "https://opensky-network.org/api/states/all"
        return try {
            val response: OpenSkyResponse = client.get(url) {
                parameter("lamin", lat - deltaLat)
                parameter("lomin", lon - deltaLon)
                parameter("lamax", lat + deltaLat)
                parameter("lomax", lon + deltaLon)
            }.body()

            response.states?.mapNotNull { state ->
                try {
                    val aLat = state[6].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                    val aLon = state[5].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                    val dist = calculateDistance(lat, lon, aLat, aLon)
                    val bearing = calculateBearing(lat, lon, aLat, aLon)
                    
                    AircraftData(
                        callsign = state[1].jsonPrimitive.content.trim().ifEmpty { "Unknown" },
                        lat = aLat,
                        lon = aLon,
                        altitudeM = state[7].jsonPrimitive.floatOrNull ?: 0f,
                        speedKts = (state[9].jsonPrimitive.floatOrNull ?: 0f) * 1.94384f,
                        heading = state[10].jsonPrimitive.floatOrNull ?: 0f,
                        distanceKm = dist.toFloat(),
                        bearingDegrees = bearing.toFloat(),
                        tailNumber = "", 
                        aircraftType = "",
                        icao24 = state[0].jsonPrimitive.content.trim().lowercase()
                    )
                } catch (e: Exception) { null }
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "OpenSky Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchAirplanesLive(lat: Double, lon: Double, radiusKm: Float): List<AircraftData> {
        val radiusNm = radiusKm * 0.539957f
        val url = "https://api.airplanes.live/v2/point/$lat/$lon/${radiusNm.toInt()}"
        
        @Serializable
        data class AliveAircraft(
            val hex: String? = null,
            val flight: String? = null,
            val r: String? = null, 
            val t: String? = null, 
            val lat: Double? = null,
            val lon: Double? = null,
            val alt_baro: JsonElement? = null,
            val gs: Float? = null,
            val track: Float? = null,
            val dist: Float? = null,
            val nav_q_orig: String? = null,
            val nav_q_dest: String? = null,
            val orig: String? = null,
            val dest: String? = null,
            val route: String? = null
        )
        
        @Serializable
        data class AliveResponse(val ac: List<AliveAircraft>? = null)

        return try {
            val response: AliveResponse = client.get(url).body()
            response.ac?.mapNotNull { ac ->
                val aLat = ac.lat ?: return@mapNotNull null
                val aLon = ac.lon ?: return@mapNotNull null
                val altM = if (ac.alt_baro is JsonPrimitive) {
                    ac.alt_baro.floatOrNull?.let { it * 0.3048f } ?: 0f
                } else 0f

                val dist = calculateDistance(lat, lon, aLat, aLon)
                val bearing = calculateBearing(lat, lon, aLat, aLon)
                
                // Parse route if available as "ORIG-DEST"
                var finalOrig = ac.orig ?: ac.nav_q_orig ?: ""
                var finalDest = ac.dest ?: ac.nav_q_dest ?: ""
                
                if (finalOrig.isEmpty() && ac.route != null) {
                    val parts = ac.route.split("-")
                    if (parts.size >= 2) {
                        finalOrig = parts[0].trim()
                        finalDest = parts[1].trim()
                    }
                }

                AircraftData(
                    callsign = ac.flight?.trim()?.ifEmpty { "Unknown" } ?: "Unknown",
                    lat = aLat,
                    lon = aLon,
                    altitudeM = altM,
                    speedKts = ac.gs ?: 0f,
                    heading = ac.track ?: 0f,
                    distanceKm = dist.toFloat(),
                    bearingDegrees = bearing.toFloat(),
                    tailNumber = ac.r ?: "",
                    aircraftType = ac.t ?: "",
                    origin = finalOrig,
                    destination = finalDest,
                    icao24 = ac.hex?.lowercase() ?: ""
                )
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "Airplanes.Live Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchAdsbExchange(lat: Double, lon: Double, radiusKm: Float): List<AircraftData> {
        val radiusNm = radiusKm * 0.539957f
        val url = "https://adsb.fi/api/v2/point/$lat/$lon/${radiusNm.toInt()}"
        
        @Serializable
        data class AeAircraft(
            val hex: String? = null,
            val flight: String? = null,
            val r: String? = null, 
            val t: String? = null, 
            val lat: Double? = null,
            val lon: Double? = null,
            val alt_baro: JsonElement? = null,
            val gs: Float? = null,
            val track: Float? = null,
            val dist: Float? = null,
            val nav_q_orig: String? = null,
            val nav_q_dest: String? = null,
            val orig: String? = null,
            val dest: String? = null,
            val route: String? = null
        )
        
        @Serializable
        data class AeResponse(val ac: List<AeAircraft>? = null)

        return try {
            val response: AeResponse = client.get(url).body()
            response.ac?.mapNotNull { ac ->
                val aLat = ac.lat ?: return@mapNotNull null
                val aLon = ac.lon ?: return@mapNotNull null
                val altM = if (ac.alt_baro is JsonPrimitive) {
                    ac.alt_baro.floatOrNull?.let { it * 0.3048f } ?: 0f
                } else 0f

                val dist = calculateDistance(lat, lon, aLat, aLon)
                val bearing = calculateBearing(lat, lon, aLat, aLon)
                
                // Parse route if available as "ORIG-DEST"
                var finalOrig = ac.orig ?: ac.nav_q_orig ?: ""
                var finalDest = ac.dest ?: ac.nav_q_dest ?: ""
                
                if (finalOrig.isEmpty() && ac.route != null) {
                    val parts = ac.route.split("-")
                    if (parts.size >= 2) {
                        finalOrig = parts[0].trim()
                        finalDest = parts[1].trim()
                    }
                }

                AircraftData(
                    callsign = ac.flight?.trim()?.ifEmpty { "Unknown" } ?: "Unknown",
                    lat = aLat,
                    lon = aLon,
                    altitudeM = altM,
                    speedKts = ac.gs ?: 0f,
                    heading = ac.track ?: 0f,
                    distanceKm = dist.toFloat(),
                    bearingDegrees = bearing.toFloat(),
                    tailNumber = ac.r ?: "",
                    aircraftType = ac.t ?: "",
                    origin = finalOrig,
                    destination = finalDest,
                    icao24 = ac.hex?.lowercase() ?: ""
                )
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "ADS-B Exchange Error: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchRoute(icao24: String): String? {
        if (icao24.isEmpty()) return null
        val url = "https://api.adsb.fi/api/v2/route/$icao24"
        return try {
            val response: JsonObject = client.get(url).body()
            response["route"]?.jsonPrimitive?.content?.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)

        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
