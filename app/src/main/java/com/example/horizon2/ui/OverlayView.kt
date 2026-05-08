package com.example.horizon2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.mutableStateOf
import com.example.horizon2.CalibrationState
import com.example.horizon2.SensorData
import com.example.horizon2.AltimeterData
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale
import java.time.*
import java.time.format.*
import kotlin.math.*

@Composable
fun OverlayView(
    sensorData: SensorData,
    altimeterData: AltimeterData = AltimeterData(),
    onAltimeterClick: () -> Unit = {},
    onCaptureClick: () -> Unit = {},
    onPlanesDistanceChange: (Float) -> Unit = {},
    onVerboseToggle: () -> Unit = {},
    onGroundedToggle: () -> Unit = {},
    appMode: String = "ALTIMETER",
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val altimeterSize = remember { mutableStateOf(IntSize.Zero) }
    val altimeterPosition = remember { mutableStateOf(Offset.Zero) }
    val textPaintYellow = remember(density) {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            alpha = (255 * 0.90f).toInt()
            textSize = with(density) { 10.dp.toPx() } // Smallest legible
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    val textPaintWhite = remember(density) {
        Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = (255 * 0.90f).toInt()
            textSize = with(density) { 10.dp.toPx() } // Smallest legible
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    val promptPaint = remember(density) {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 16.dp.toPx() }
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    fun convertTime(utcTime: String): String {
        return try {
            // Try parsing as ISO instant first (e.g., 2026-05-06T12:00:00Z)
            val instant = Instant.parse(utcTime)
            val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            val zone = ZoneId.systemDefault()
            val shortId = zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            "${localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))} $shortId"
        } catch (_: Exception) {
            try {
                // Fallback to custom format (e.g., 2026-05-06 12:00:00)
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val utcDateTime = LocalDateTime.parse(utcTime, formatter)
                val instant = utcDateTime.toInstant(ZoneOffset.UTC)
                val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                val zone = ZoneId.systemDefault()
                val shortId = zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "${localDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))} $shortId"
            } catch (_: Exception) {
                // If all fail, return original
                utcTime
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerX = width / 2
            val centerY = height / 2
            
            val vFov = 60f 
            val sensitivity = height / vFov

            // 1. Dynamic opaque black overlay
            drawRect(color = ComposeColor.Black, alpha = sensorData.overlayAlpha, size = size)

            // 2. Green Layer (Fused)
            val barThickness = width * 0.05f
            if (sensorData.isFlat) {
                // Bubble Display for Flat orientation
                val bubbleX = centerX + (sensorData.greenBubbleX * sensitivity)
                val bubbleY = centerY + (sensorData.greenBubbleY * sensitivity)
                // Unfilled Green Bubble
                drawCircle(color = ComposeColor.Green, alpha = 0.5f, radius = 25.dp.toPx(), center = Offset(bubbleX, bubbleY), style = Stroke(width = 2.dp.toPx()))
                
                // Central bullseye
                drawCircle(color = ComposeColor.Green, alpha = 0.3f, radius = 10.dp.toPx(), center = Offset(centerX, centerY))
                drawLine(color = ComposeColor.Green, alpha = 0.15f, start = Offset(centerX - 20.dp.toPx(), centerY), end = Offset(centerX + 20.dp.toPx(), centerY), strokeWidth = 1.dp.toPx())
                drawLine(color = ComposeColor.Green, alpha = 0.15f, start = Offset(centerX, centerY - 20.dp.toPx()), end = Offset(centerX, centerY + 20.dp.toPx()), strokeWidth = 1.dp.toPx())
            } else {
                // Normal Line Display
                rotate(degrees = sensorData.roll, pivot = Offset(centerX, centerY)) {
                    val horizonY = centerY + (sensorData.pitch * sensitivity)
                    
                    drawRect(color = ComposeColor.Green, alpha = 0.20f, topLeft = Offset(-width * 2, horizonY - (barThickness / 2)), size = androidx.compose.ui.geometry.Size(width * 5, barThickness))
                    drawRect(color = ComposeColor.Green, alpha = 0.20f, topLeft = Offset(centerX - (barThickness / 2), -height * 2), size = androidx.compose.ui.geometry.Size(barThickness, height * 5))
                    drawCircle(color = ComposeColor.Yellow, alpha = 0.11f, radius = barThickness, center = Offset(centerX, horizonY))
                }
            }

            // 3. White HUD (Gyro-based)
            if (sensorData.hasBeenCalibrated) {
                if (sensorData.isFlat) {
                    // White Bubble (Unfilled)
                    val bubbleX = centerX + (sensorData.whiteBubbleX * sensitivity)
                    val bubbleY = centerY + (sensorData.whiteBubbleY * sensitivity)
                    drawCircle(color = ComposeColor.White, alpha = 0.8f, radius = 20.dp.toPx(), center = Offset(bubbleX, bubbleY), style = Stroke(width = 3.dp.toPx()))
                } else {
                    rotate(degrees = sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
                        val trueY = centerY + (sensorData.truePitch * sensitivity)
                        
                        // Horizontal line
                        drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(-width * 2, trueY), end = Offset(width * 5, trueY), strokeWidth = 2.dp.toPx())
                        
                        // Horizontal Ticks (every 10 degrees)
                        for (angle in 0 until 360 step 10) {
                            val delta = (angle - sensorData.trueHeading + 540) % 360 - 180
                            val tickX = centerX + (delta * sensitivity)
                            if (tickX in -width..width * 2) {
                                drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(tickX, trueY - 10.dp.toPx()), end = Offset(tickX, trueY + 10.dp.toPx()), strokeWidth = 2.dp.toPx())
                                
                                if (sensorData.isGpsCalibrated || sensorData.isManualCalibrated) {
                                    rotate(degrees = -sensorData.trueRoll, pivot = Offset(tickX, trueY - 15.dp.toPx())) {
                                        drawContext.canvas.nativeCanvas.drawText("$angle", tickX, trueY - 15.dp.toPx(), promptPaint)
                                    }
                                }
                            }
                        }

                        // Vertical line
                        drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(centerX, -height * 2), end = Offset(centerX, height * 5), strokeWidth = 2.dp.toPx())
                        
                        // Vertical Ticks (every 15 degrees)
                        for (pitch in -180..180 step 15) {
                            val delta = pitch - sensorData.trueFullPitch
                            val tickY = centerY - (delta * sensitivity)
                            if (tickY in -height..height * 2) {
                                drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(centerX - 10.dp.toPx(), tickY), end = Offset(centerX + 10.dp.toPx(), tickY), strokeWidth = 2.dp.toPx())
                                
                                rotate(degrees = -sensorData.trueRoll, pivot = Offset(centerX + 60.dp.toPx(), tickY + 5.dp.toPx())) {
                                    val label = (if (pitch > 90) 180 - pitch else if (pitch < -90) -180 - pitch else pitch)
                                    drawContext.canvas.nativeCanvas.drawText(label.toString(), centerX + 60.dp.toPx(), tickY + 5.dp.toPx(), textPaintWhite)
                                }
                            }
                        }
                    }
                }
            }

            // Prompts
            val promptText = when {
                !sensorData.hasBeenCalibrated -> "KEEP DEVICE STILL TO CALIBRATE"
                !sensorData.isGpsCalibrated && !sensorData.isManualCalibrated -> "MOVE TO GPS CALIBRATE OR USE MANUAL"
                else -> "READY"
            }
            if (promptText.isNotEmpty()) drawContext.canvas.nativeCanvas.drawText(promptText, centerX, 60.dp.toPx(), promptPaint)

            // AIRCRAFT HUD POPULATION
            if (appMode == "PLANES") {
                val hFov = 60f 
                val vFov = 45f 
                val hSensitivity = width / hFov
                val vSensitivity = height / vFov

                sensorData.nearbyAircraft.forEach { aircraft ->
                    // 1. Filtering
                    val isTooSlow = !sensorData.showGrounded && aircraft.speedKts < 20
                    val radiusKm = sensorData.planesDistance / 2f
                    val isOutOfRange = aircraft.distanceKm > radiusKm
                    if (isTooSlow || isOutOfRange) return@forEach

                    // 2. Calculate Azimuth relative to User Heading
                    val currentHeading = if (sensorData.isGpsCalibrated || sensorData.isManualCalibrated) {
                        sensorData.trueHeading
                    } else {
                        sensorData.heading
                    }
                    
                    val azDiff = (aircraft.bearingDegrees - currentHeading + 540) % 360 - 180
                    
                    // 3. Calculate Elevation relative to Horizon
                    val altDiffM = aircraft.altitudeM - 0f 
                    val distanceM = aircraft.distanceKm * 1000f
                    val elevationAngle = Math.toDegrees(atan2(altDiffM.toDouble(), distanceM.toDouble())).toFloat()
                    val elDiff = elevationAngle - sensorData.truePitch

                    // 4. Project to Screen Coordinates
                    rotate(degrees = sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
                        val screenX = centerX + (azDiff * hSensitivity)
                        val screenY = centerY - (elDiff * vSensitivity) 

                        if (screenX in 0f..width && screenY in 0f..height) {
                            drawCircle(
                                color = ComposeColor.Yellow,
                                radius = 3.dp.toPx(),
                                center = Offset(screenX, screenY)
                            )

                            // Column label below the dot
                            val flightInfo = if (sensorData.isVerbose) {
                                "${expandAirline(aircraft.callsign)} ${aircraft.tailNumber}"
                            } else {
                                "${aircraft.callsign} ${aircraft.tailNumber}"
                            }
                            
                            val typeInfo = if (sensorData.isVerbose) {
                                "${expandType(aircraft.aircraftType)} - ${aircraft.distanceKm.toInt()}km"
                            } else {
                                "${aircraft.aircraftType} - ${aircraft.distanceKm.toInt()}km"
                            }
                            
                            val metrics = "${aircraft.speedKts.toInt()}kts ${aircraft.heading.toInt()}° ${aircraft.altitudeM.toInt()}m"
                            
                            val rowHeight = 12.dp.toPx()
                            drawContext.canvas.nativeCanvas.drawText(flightInfo, screenX, screenY + rowHeight, textPaintYellow)
                            drawContext.canvas.nativeCanvas.drawText(typeInfo, screenX, screenY + rowHeight * 2, textPaintWhite)
                            drawContext.canvas.nativeCanvas.drawText(metrics, screenX, screenY + rowHeight * 3, textPaintWhite)
                        }
                    }
                }
            }
        }

        // 4. Altimeter HUD (Bottom Left)
         Column(
             modifier = Modifier
                 .align(Alignment.BottomStart)
                 .padding(16.dp)
                 .navigationBarsPadding()
                 .clickable { onAltimeterClick() }
                 .onGloballyPositioned { coordinates ->
                     altimeterSize.value = coordinates.size
                     altimeterPosition.value = coordinates.localToWindow(Offset.Zero)
                 }
         ) {
            if (sensorData.overlayAlpha <= 0.5f && sensorData.calibrationState == CalibrationState.CALIBRATED) {
                androidx.compose.material3.Button(onClick = onCaptureClick) {
                    Text("CAPTURE")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = "${"%.1f".format(altimeterData.rawPressureHpa)} hPa",
                color = ComposeColor.Yellow,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "${altimeterData.correctedAltitudeM} m",
                color = ComposeColor.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = altimeterData.status,
                color = ComposeColor.Gray,
                style = MaterialTheme.typography.labelSmall
            )
            
            // Consolidated Heading Block
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "MAG ${sensorData.headingString}",
                color = ComposeColor.Green,
                style = MaterialTheme.typography.titleSmall
            )
            if (sensorData.isManualCalibrated) {
                Text(
                    text = "MAN ${sensorData.manualHeadingString}",
                    color = ComposeColor.Yellow,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (sensorData.isGpsCalibrated) {
                Text(
                    text = "MOV ${sensorData.gpsHeadingString}",
                    color = ComposeColor.White,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }

        // 5. Airport Overlay
        if (altimeterData.isDetailVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ComposeColor.Black.copy(alpha = 0.8f))
                    .clickable { onAltimeterClick() }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ComposeColor.DarkGray.copy(alpha = 0.9f))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Nearest Airports",
                        color = ComposeColor.White,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    altimeterData.airports.take(5).forEach { airport ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${airport.code} ${airport.distanceKm.toInt()}km ${airport.pressureHpa.toInt()}hPa ${airport.elevationM.toInt()}m ${convertTime(airport.reportTime)}",
                                color = ComposeColor.White,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // 6. PLANES Mode Overlay
         if (appMode == "PLANES") {
             PlanesOverlay(
                 altimeterSize = altimeterSize.value,
                 sensorData = sensorData,
                 onDistanceChange = onPlanesDistanceChange,
                 onVerboseToggle = onVerboseToggle,
                 onGroundedToggle = onGroundedToggle
             )
         }
    }
}

@Composable
fun PlanesOverlay(
    altimeterSize: IntSize = IntSize.Zero,
    sensorData: SensorData,
    onDistanceChange: (Float) -> Unit,
    onVerboseToggle: () -> Unit,
    onGroundedToggle: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (altimeterSize != IntSize.Zero) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 100.dp)
                        .navigationBarsPadding()
                        .width(260.dp)
                        .height(250.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 1. Opaque Dark Green Circle with Geometric Markings and Aircraft Map
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(180.dp)
                                .background(
                                    color = ComposeColor(0, 50, 0),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val radius = size.width / 2
                                val center = Offset(size.width / 2, size.height / 2)
                                
                                // Three concentric white rings (radii: 30dp, 60dp, 90dp)
                                val ringRadii = listOf(30.dp.toPx(), 60.dp.toPx(), 90.dp.toPx())
                                ringRadii.forEach { r ->
                                    drawCircle(
                                        color = ComposeColor.White,
                                        radius = r,
                                        center = center,
                                        style = Stroke(width = 1.dp.toPx()),
                                        alpha = 0.5f
                                    )
                                }
                                
                                // Radial lines every 30 degrees
                                for (angle in 0 until 360 step 30) {
                                    val rad = Math.toRadians(angle.toDouble())
                                    val endX = center.x + radius * cos(rad).toFloat()
                                    val endY = center.y + radius * sin(rad).toFloat()
                                    drawLine(
                                        color = ComposeColor.White,
                                        start = center,
                                        end = Offset(endX, endY),
                                        strokeWidth = 1.dp.toPx(),
                                        alpha = 0.3f
                                    )
                                }

                                // 45-degree green wedge pointing to bearing
                                val currentHeading = if (sensorData.isGpsCalibrated || sensorData.isManualCalibrated) {
                                    sensorData.trueHeading
                                } else {
                                    sensorData.heading
                                }
                                
                                val startAngle = currentHeading - 90f - 22.5f
                                drawArc(
                                    color = ComposeColor(0, 100, 0),
                                    startAngle = startAngle,
                                    sweepAngle = 45f,
                                    useCenter = true,
                                    alpha = 0.5f
                                )

                                // AIRCRAFT MAPPING (Radar Map)
                                val mapRadiusKm = sensorData.planesDistance / 2f
                                if (mapRadiusKm > 0) {
                                    sensorData.nearbyAircraft.forEach { aircraft ->
                                        // Radar Map: Show ALL fetched planes regardless of slider if they fit the circle boundary
                                        // Wait, the user said: "keep them all available to plot if the slider range is changed."
                                        // and "On HUD, show only planes within range"
                                        // Usually radar maps scale with the slider. 
                                        // I'll keep the radar scaling with slider, but HUD filtered by slider.
                                        
                                        val distRatio = aircraft.distanceKm / mapRadiusKm
                                        if (distRatio <= 1.0f) {
                                            val angleRad = Math.toRadians((aircraft.bearingDegrees - 90f).toDouble())
                                            val x = center.x + (distRatio * radius * cos(angleRad).toFloat())
                                            val y = center.y + (distRatio * radius * sin(angleRad).toFloat())
                                            
                                            // Check grounded toggle for radar too? 
                                            // "Add a toggle switch for "grounded" to show/ignore planes moving below 20 knots."
                                            if (sensorData.showGrounded || aircraft.speedKts >= 20) {
                                                drawCircle(
                                                    color = ComposeColor.White,
                                                    radius = 2.dp.toPx(),
                                                    center = Offset(x, y)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Control Toggles (Moved left to avoid slider interference)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)//do not change
                                .padding(start = 150.dp, bottom = 10.dp) //do not change
                                .width(80.dp),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(120.dp) //do not change
                        ) {
                            // Verbose Toggle
                            Column(verticalArrangement = Arrangement.spacedBy((-10).dp)) { //do not change
                                Text(
                                    "Verbose", color = ComposeColor.White, style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        " ", color = ComposeColor.White, style = MaterialTheme.typography.labelSmall)
                                    Switch(
                                        checked = sensorData.isVerbose,
                                        onCheckedChange = { onVerboseToggle() },
                                        modifier = Modifier.scale(0.9f), //DO NOT CHANGE
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ComposeColor.Yellow,
                                            uncheckedThumbColor = ComposeColor.Gray,
                                            checkedTrackColor = ComposeColor.DarkGray,
                                            uncheckedTrackColor = ComposeColor.Black
                                        )
                                    )
                                }
                            }

                            // Grounded Toggle
                            Column(verticalArrangement = Arrangement.spacedBy((-10).dp)) { //do not change
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        " ", color = ComposeColor.White, style = MaterialTheme.typography.labelSmall)
                                    Switch(
                                        checked = sensorData.showGrounded,
                                        onCheckedChange = { onGroundedToggle() },
                                        modifier = Modifier.scale(0.9f), //DO NOT CHANGE
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ComposeColor.Green,
                                            uncheckedThumbColor = ComposeColor.Gray,
                                            checkedTrackColor = ComposeColor.DarkGray,
                                            uncheckedTrackColor = ComposeColor.Black
                                        )
                                    )
                                }
                                Text("Grounded", color = ComposeColor.White, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        // 3. Logarithmic Slider and Distance Label
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(260.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopStart
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(top = 10.dp, start = 0.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "${sensorData.planesDistance.toInt()}km",
                                    color = ComposeColor.White,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                                
                                val shortSource = when (sensorData.lastAdbSource) {
                                    "OpenSky" -> "OS"
                                    "ADS-B Exchange" -> "AE"
                                    "Airplanes.Live" -> "AL"
                                    else -> "N/A"
                                }

                                Text(
                                    text = sensorData.lastAdbUpdateTime,
                                    color = ComposeColor.Gray,
                                    style = MaterialTheme.typography.labelSmall
                                )

                                Text(
                                    text = shortSource,
                                    color = ComposeColor.Gray,
                                    style = MaterialTheme.typography.labelSmall
                                )

                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(32.dp)
                                    .align(Alignment.CenterEnd),
                                contentAlignment = Alignment.Center
                            ) {
                                Slider(
                                    value = sensorData.planesDistanceValue,
                                    onValueChange = onDistanceChange,
                                    modifier = Modifier
                                        .requiredWidth(250.dp)
                                        .graphicsLayer {
                                            rotationZ = -90f
                                        },
                                    colors = androidx.compose.material3.SliderDefaults.colors(
                                        thumbColor = ComposeColor.Yellow,
                                        activeTrackColor = ComposeColor.Gray,
                                        inactiveTrackColor = ComposeColor.DarkGray
                                    )
                                )
                            }
                        }
                    }
                }
        }
    }
}

private fun expandAirline(callsign: String): String {
    val upper = callsign.uppercase()
    return when {
        upper.startsWith("UAL") -> "United"
        upper.startsWith("AAL") -> "American"
        upper.startsWith("DAL") -> "Delta"
        upper.startsWith("SWA") -> "Southwest"
        upper.startsWith("JBU") -> "JetBlue"
        upper.startsWith("BAW") -> "British"
        upper.startsWith("DLH") -> "Lufthansa"
        upper.startsWith("AFR") -> "Air France"
        upper.startsWith("UPS") -> "UPS"
        upper.startsWith("FDX") -> "FedEx"
        else -> callsign
    }
}

private fun expandType(type: String): String {
    val upper = type.uppercase()
    return when (upper) {
        "B738" -> "Boeing 737-800"
        "B737" -> "Boeing 737"
        "B739" -> "Boeing 737-900"
        "A320" -> "Airbus A320"
        "A321" -> "Airbus A321"
        "A319" -> "Airbus A319"
        "B772" -> "Boeing 777-200"
        "B77W" -> "Boeing 777-300ER"
        "B788" -> "Boeing 787-8"
        "B789" -> "Boeing 787-9"
        "A359" -> "Airbus A350-900"
        "CRJ7" -> "Bombardier CRJ-700"
        "CRJ9" -> "Bombardier CRJ-900"
        "E175" -> "Embraer 175"
        "E190" -> "Embraer 190"
        else -> type
    }
}




















