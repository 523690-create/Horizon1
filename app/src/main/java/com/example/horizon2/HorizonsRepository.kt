package com.example.horizon2

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class HorizonsResponse(
    val result: String? = null
)

class HorizonsRepository {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val planets = mapOf(
        "Mercury" to "199",
        "Venus" to "299",
        "Mars" to "499",
        "Jupiter" to "599",
        "Saturn" to "699",
        "Uranus" to "799",
        "Neptune" to "899"
    )

    suspend fun fetchPlanetPositions(lat: Double, lon: Double): List<CelestialObject> {
        val results = mutableListOf<CelestialObject>()
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val now = Date()
        val startTime = sdf.format(now)
        val stopTime = sdf.format(Date(now.time + 60000)) // 1 minute later

        for ((name, id) in planets) {
            try {
                val url = buildUrl(id, lat, lon, startTime, stopTime)
                val response: HorizonsResponse = client.get(url).body()
                val text = response.result ?: continue
                
                // Parse the ASCII result for Azimuth and Altitude
                // Format is usually between $$SOE and $$EOE
                val azAlt = parseAzAlt(text)
                if (azAlt != null) {
                    results.add(CelestialObject(name, azAlt.first, azAlt.second, 0f, "planet"))
                }
            } catch (e: Exception) {
                android.util.Log.e("HorizonsRepo", "Error fetching $name: ${e.message}")
            }
        }
        return results
    }

    private fun buildUrl(id: String, lat: Double, lon: Double, start: String, stop: String): String {
        val baseUrl = "https://ssd.jpl.nasa.gov/api/horizons.api"
        val params = mapOf(
            "format" to "json",
            "COMMAND" to "'$id'",
            "MAKE_EPHEM" to "'YES'",
            "EPHEM_TYPE" to "'OBSERVER'",
            "CENTER" to "'coord'",
            "COORD_TYPE" to "'GEODETIC'",
            "SITE_COORD" to "'$lon,$lat,0'",
            "START_TIME" to "'$start'",
            "STOP_TIME" to "'$stop'",
            "STEP_SIZE" to "'1m'",
            "QUANTITIES" to "'4'", // 4: Az/El
            "ANG_FORMAT" to "'DEG'"
        )

        return baseUrl + "?" + params.entries.joinToString("&") { 
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" 
        }
    }

    private fun parseAzAlt(text: String): Pair<Float, Float>? {
        // Look for data between $$SOE and $$EOE
        val startMarker = "\$\$SOE"
        val endMarker = "\$\$EOE"
        val startIndex = text.indexOf(startMarker)
        val endIndex = text.indexOf(endMarker)
        
        if (startIndex == -1 || endIndex == -1) return null
        
        val dataBlock = text.substring(startIndex + startMarker.length, endIndex).trim()
        val lines = dataBlock.lines()
        if (lines.isEmpty()) return null
        
        // Typical line: 2026-05-06 14:00 *m  270.1234  15.456 ...
        // Columns: Date/Time, solar-presence-flag, Azimuth, Elevation
        val parts = lines[0].trim().split(Regex("\\s+"))
        if (parts.size >= 4) {
            val az = parts[2].toFloatOrNull()
            val alt = parts[3].toFloatOrNull()
            if (az != null && alt != null) {
                return Pair(az, alt)
            }
        }
        return null
    }
}
