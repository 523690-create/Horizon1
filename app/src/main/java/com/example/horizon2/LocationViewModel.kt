package com.example.horizon2

import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class LocationData(
    val bearing: Float = 0f,
    val speed: Float = 0f,
    val hasMovement: Boolean = false,
    val bearingAccuracy: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val verticalAccuracy: Float = 0f,
)

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    
    private val _locationState = MutableStateFlow(LocationData())
    val locationState: StateFlow<LocationData> = _locationState.asStateFlow()

    private var currentIntervalMillis = 5000L
    private var currentMinIntervalMillis = 2000L
    private var locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMillis)
        .setMinUpdateIntervalMillis(currentMinIntervalMillis)
        .build()

    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                _locationState.value = LocationData(
                    bearing = if (location.hasBearing()) location.bearing else 0f,
                    speed = location.speed,
                    hasMovement = location.speed > 0.5f,
                    bearingAccuracy = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else 10f,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    verticalAccuracy = location.verticalAccuracyMeters,
                )
                // Dynamic location update adjustment based on speed
                val newInterval = if (_locationState.value.speed > 5f) 2000L else 5000L
                val newMinInterval = if (_locationState.value.speed > 5f) 1000L else 2000L
                updateLocationRequestIfNeeded(newInterval, newMinInterval)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationRequestIfNeeded(newInterval: Long, newMinInterval: Long) {
        if (newInterval != currentIntervalMillis) {
            currentIntervalMillis = newInterval
            currentMinIntervalMillis = newMinInterval
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMillis)
                .setMinUpdateIntervalMillis(currentMinIntervalMillis)
                .build()
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
    }
}




