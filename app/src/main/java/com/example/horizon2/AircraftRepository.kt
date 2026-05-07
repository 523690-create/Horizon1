package com.example.horizon2

import io.ktor.client.*
import io.ktor.client.call.*
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
    }

    // Enrichment Data Maps
    private val airlineMap = mutableMapOf<String, String>() // ICAO -> Name
    private val aircraftMap = mutableMapOf<String, String>() // ICAO Type -> Name
    private val routeMap = mutableMapOf<String, Pair<String, String>>() // Callsign -> Origin/Dest

    suspend fun checkConnectivity(): Boolean {
        return try {
            val response = client.get("https://www.google.com")
            android.util.Log.d("AircraftRepo", "Connectivity check: ${response.status}")
            response.status.value in 200..299
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "Connectivity check failed: ${e.message}")
            false
        }
    }

    suspend fun fetchEnrichmentData() {
        android.util.Log.d("AircraftRepo", "Starting data enrichment download...")
        
        // 1. Airlines (OpenFlights)
        try {
            val airlines = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/airlines.dat").bodyAsText()
            airlines.lineSequence().forEach { line ->
                val parts = line.split(",")
                if (parts.size > 4) {
                    val name = parts[1].replace("\"", "").trim()
                    val icao = parts[4].replace("\"", "").trim()
                    if (icao.length == 3) airlineMap[icao] = name
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${airlineMap.size} airlines")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Airlines fetch failed") }

        // 2. Aircraft (OpenTravelData)
        try {
            val aircraft = client.get("https://raw.githubusercontent.com/opentraveldata/opentraveldata/master/opentraveldata/optd_aircraft.csv").bodyAsText()
            aircraft.lineSequence().forEach { line ->
                val parts = line.split("^")
                if (parts.size > 2) {
                    val icao = parts[0].trim()
                    val name = parts[1].trim()
                    aircraftMap[icao] = name
                }
            }
            android.util.Log.d("AircraftRepo", "Loaded ${aircraftMap.size} aircraft types")
        } catch (e: Exception) { android.util.Log.e("AircraftRepo", "Aircraft fetch failed") }

        // 3. Routes (OpenFlights)
        try {
            val routes = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/routes.dat").bodyAsText()
            routes.lineSequence().forEach { line ->
                val parts = line.split(",")
                if (parts.size > 4) {
                    val airlineIcao = parts[0].trim()
                    val origin = parts[2].trim()
                    val dest = parts[4].trim()
                    // We don't have flight numbers easily here, but we can index by Airline+Something?
                    // Actually callsign is often AirlineICAO + FlightNumber.
                    // This is hard to map perfectly without a full schedule API.
                }
            }
        } catch (e: Exception) { }
    }

    fun getAirlineName(callsign: String): String? {
        if (callsign.length < 3) return null
        val icao = callsign.substring(0, 3)
        return airlineMap[icao]
    }

    fun getAircraftName(type: String): String? {
        return aircraftMap[type]
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
                    
                    val callsign = state[1].jsonPrimitive.content.trim()
                    
                    AircraftData(
                        callsign = callsign.ifEmpty { "Unknown" },
                        lat = aLat,
                        lon = aLon,
                        altitudeM = state[7].jsonPrimitive.floatOrNull ?: 0f,
                        speedKts = (state[9].jsonPrimitive.floatOrNull ?: 0f) * 1.94384f,
                        heading = state[10].jsonPrimitive.floatOrNull ?: 0f,
                        distanceKm = dist.toFloat(),
                        bearingDegrees = bearing.toFloat(),
                        tailNumber = "", 
                        aircraftType = "" 
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
            val flight: String? = null,
            val r: String? = null, 
            val t: String? = null, 
            val lat: Double? = null,
            val lon: Double? = null,
            val alt_baro: JsonElement? = null,
            val gs: Float? = null,
            val track: Float? = null,
            val dist: Float? = null
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
                    aircraftType = ac.t ?: ""
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
            val flight: String? = null,
            val r: String? = null, 
            val t: String? = null,
            val lat: Double? = null,
            val lon: Double? = null,
            val alt_baro: JsonElement? = null,
            val gs: Float? = null,
            val track: Float? = null,
            val dist: Float? = null
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
                    aircraftType = ac.t ?: ""
                )
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AircraftRepo", "ADS-B Exchange Error: ${e.message}")
            emptyList()
        }
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
