package com.example.horizon1

import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

data class AirportRef(
    val icao: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevM: Double
)

object AirportDb {
    private var airports = listOf<AirportRef>()
    private val mutex = Mutex()
    var isLoaded = false
        private set

    suspend fun load(client: HttpClient) {
        mutex.withLock {
            if (isLoaded) return
            try {
                Log.d("AirportDb", "Starting download...")
                val response: String = client.get("https://raw.githubusercontent.com/jpatokal/openflights/master/data/airports.dat").bodyAsText()
                val lines = response.lines()
                val parsed = mutableListOf<AirportRef>()
                
                for (line in lines) {
                    if (line.isBlank()) continue
                    // Format: ID, Name, City, Country, IATA, ICAO, Lat, Lon, Alt, ...
                    // Split by comma, but handle quotes (simplified for this format)
                    val parts = line.split(",")
                    if (parts.size < 10) continue
                    
                    // ICAO is at index 5, Lat at 6, Lon at 7, Alt (ft) at 8
                    // Strip quotes from ICAO
                    val icao = parts[5].replace("\"", "").trim()
                    if (icao.length != 4 || icao == "\\N") continue
                    
                    val name = parts[1].replace("\"", "").trim()
                    val lat = parts[6].toDoubleOrNull()
                    val lon = parts[7].toDoubleOrNull()
                    val altFt = parts[8].toDoubleOrNull()
                    
                    if (lat != null && lon != null) {
                        parsed.add(AirportRef(
                            icao = icao,
                            name = name,
                            lat = lat,
                            lon = lon,
                            elevM = (altFt ?: 0.0) * 0.3048
                        ))
                    }
                }
                val distinctAirports = parsed.distinctBy { it.icao }
                airports = distinctAirports
                isLoaded = true
                Log.d("AirportDb", "Loaded ${airports.size} distinct airports")
            } catch (e: Exception) {
                Log.e("AirportDb", "Failed to load airports", e)
            }
        }
    }

    fun getClosestAirports(lat: Double, lon: Double, count: Int): List<AirportRef> {
        return airports.sortedBy { calculateDistance(lat, lon, it.lat, it.lon) }.take(count)
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
}
