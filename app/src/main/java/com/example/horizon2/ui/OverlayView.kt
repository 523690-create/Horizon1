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
    modifier: Modifier = Modifier,
    sensorData: SensorData,
    altimeterData: AltimeterData = AltimeterData(),
    onAltimeterClick: () -> Unit = {},
    onCaptureClick: () -> Unit = {},
    onPlanesDistanceChange: (Float) -> Unit = {},
    onVerboseToggle: () -> Unit = {},
    onGroundedToggle: () -> Unit = {},
    onConstellationToggle: () -> Unit = {},
    onRadarClick: () -> Unit = {},
    appMode: String = "ALTIMETER"
) {
    val density = LocalDensity.current
    val altimeterSize = remember { mutableStateOf(IntSize.Zero) }
    val altimeterPosition = remember { mutableStateOf(Offset.Zero) }
    
    val textPaintYellow = remember(density) {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            alpha = (255 * 0.90f).toInt()
            textSize = with(density) { 10.dp.toPx() }
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    val textPaintWhite = remember(density) {
        Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = (255 * 0.90f).toInt()
            textSize = with(density) { 10.dp.toPx() }
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
            
            // Unified FOV and Sensitivity
            val hFov = 60f
            val vFov = 45f 
            val hSensitivity = width / hFov
            val vSensitivity = height / vFov

            // 1. Dynamic opaque black overlay
            drawRect(color = ComposeColor.Black, alpha = sensorData.overlayAlpha, size = size)

            // 2. Green Layer (Fused)
            val barThickness = width * 0.05f
            if (sensorData.isFlat) {
                // Bubble Display for Flat orientation
                val bubbleX = centerX + (sensorData.greenBubbleX * hSensitivity)
                val bubbleY = centerY + (sensorData.greenBubbleY * vSensitivity)
                // Unfilled Green Bubble
                drawCircle(color = ComposeColor.Green, alpha = 0.5f, radius = 25.dp.toPx(), center = Offset(bubbleX, bubbleY), style = Stroke(width = 2.dp.toPx()))

                // Central bullseye
                drawCircle(color = ComposeColor.Green, alpha = 0.3f, radius = 10.dp.toPx(), center = Offset(centerX, centerY))
                drawLine(color = ComposeColor.Green, alpha = 0.15f, start = Offset(centerX - 20.dp.toPx(), centerY), end = Offset(centerX + 20.dp.toPx(), centerY), strokeWidth = 1.dp.toPx())
                drawLine(color = ComposeColor.Green, alpha = 0.15f, start = Offset(centerX, centerY - 20.dp.toPx()), end = Offset(centerX, centerY + 20.dp.toPx()), strokeWidth = 1.dp.toPx())
            } else {
                // Normal Line Display
                rotate(degrees = sensorData.roll, pivot = Offset(centerX, centerY)) {
                    val horizonY = centerY + (sensorData.pitch * vSensitivity)

                    drawRect(color = ComposeColor.Green, alpha = 0.20f, topLeft = Offset(-width * 2, horizonY - (barThickness / 2)), size = androidx.compose.ui.geometry.Size(width * 5, barThickness))
                    drawRect(color = ComposeColor.Green, alpha = 0.20f, topLeft = Offset(centerX - (barThickness / 2), -height * 2), size = androidx.compose.ui.geometry.Size(barThickness, height * 5))
                    drawCircle(color = ComposeColor.Yellow, alpha = 0.11f, radius = barThickness, center = Offset(centerX, horizonY))
                }
            }

            // 3. White HUD (Gyro-based)
            if (sensorData.hasBeenCalibrated) {
                if (sensorData.isFlat) {
                    // White Bubble (Unfilled)
                    val bubbleX = centerX + (sensorData.whiteBubbleX * hSensitivity)
                    val bubbleY = centerY + (sensorData.whiteBubbleY * vSensitivity)
                    drawCircle(color = ComposeColor.White, alpha = 0.8f, radius = 20.dp.toPx(), center = Offset(bubbleX, bubbleY), style = Stroke(width = 3.dp.toPx()))
                } else {
                    rotate(degrees = sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
                        val trueY = centerY + (sensorData.truePitch * vSensitivity)

                        // Horizontal line
                        drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(-width * 2, trueY), end = Offset(width * 5, trueY), strokeWidth = 2.dp.toPx())
                        
                        // Horizontal Ticks (every 10 degrees)
                        for (angle in 0 until 360 step 10) {
                            val delta = (angle - sensorData.trueHeading + 540) % 360 - 180
                            val tickX = centerX + (delta * hSensitivity)
                            if (tickX in -width..width * 2) {
                                drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(tickX, trueY - 10.dp.toPx()), end = Offset(tickX, trueY + 10.dp.toPx()), strokeWidth = 2.dp.toPx())

                                if (sensorData.isGpsCalibrated || sensorData.isManualCalibrated) {
                                    rotate(degrees = -sensorData.trueRoll, pivot = Offset(tickX, trueY - 15.dp.toPx())) {
                                        drawContext.canvas.nativeCanvas.drawText(angle.toString(), tickX, trueY - 15.dp.toPx(), promptPaint)
                                    }
                                }
                            }
                        }

                        // Vertical line
                        drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(centerX, -height * 2), end = Offset(centerX, height * 5), strokeWidth = 2.dp.toPx())

                        // Vertical Ticks (every 15 degrees)
                        for (pitch in -180..180 step 15) {
                            val delta = pitch - sensorData.trueFullPitch
                            val tickY = centerY - (delta * vSensitivity)
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
                sensorData.nearbyAircraft.forEach { aircraft ->
                    // 1. Filtering
                    val isTooSlow = !sensorData.showGrounded && aircraft.speedKts < 20
                    val radiusKm = sensorData.planesDistance / 2f
                    val isOutOfRange = aircraft.distanceKm > radiusKm
                    if (isTooSlow || isOutOfRange) return@forEach

                    // 2. Calculate Azimuth relative to User Heading
                    val azDiff = (aircraft.bearingDegrees - sensorData.trueHeading + 540) % 360 - 180

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
                            drawCircle(color = ComposeColor.Yellow, radius = 3.dp.toPx(), center = Offset(screenX, screenY))
                            
                            val rowHeight = 12.dp.toPx()
                            
                            // Row 1: flight number, type abbreviation, speed
                            val row1 = "${aircraft.callsign} ${aircraft.aircraftType} ${aircraft.speedKts.toInt()}kts"
                            // Row 2: altitude, heading, distance
                            val row2 = "${aircraft.altitudeM.toInt()}m ${aircraft.heading.toInt()}° ${aircraft.distanceKm.toInt()}km"
                            
                            drawContext.canvas.nativeCanvas.drawText(row1, screenX, screenY + rowHeight, textPaintYellow)
                            drawContext.canvas.nativeCanvas.drawText(row2, screenX, screenY + rowHeight * 2, textPaintWhite)

                            if (sensorData.isVerbose) {
                                // Row 3: airline (expanded)
                                val row3 = aircraft.airlineName.ifEmpty { expandAirline(aircraft.callsign) }
                                // Row 4: aircraft type (expanded)
                                val row4 = aircraft.aircraftTypeName.ifEmpty { expandType(aircraft.aircraftType) }
                                // Row 5: origin to destination
                                val row5 = if (aircraft.origin.isNotEmpty() || aircraft.destination.isNotEmpty()) {
                                    "${aircraft.origin.ifEmpty { "?" }} to ${aircraft.destination.ifEmpty { "?" }}"
                                } else ""

                                drawContext.canvas.nativeCanvas.drawText(row3, screenX, screenY + rowHeight * 3, textPaintWhite)
                                drawContext.canvas.nativeCanvas.drawText(row4, screenX, screenY + rowHeight * 4, textPaintWhite)
                                if (row5.isNotEmpty()) {
                                    drawContext.canvas.nativeCanvas.drawText(row5, screenX, screenY + rowHeight * 5, textPaintWhite)
                                }
                            }
                        }
                    }
                }
            }

            // CELESTIAL HUD POPULATION
            if (appMode == "STARS") {
                // 1. Draw Constellation Lines
                if (sensorData.showConstellations) {
                    sensorData.constellationLines.forEach { (s1, s2) ->
                        val azDiff1 = (s1.azimuth - sensorData.trueHeading + 540) % 360 - 180
                        val elDiff1 = s1.altitude - sensorData.truePitch
                        val azDiff2 = (s2.azimuth - sensorData.trueHeading + 540) % 360 - 180
                        val elDiff2 = s2.altitude - sensorData.truePitch

                        rotate(degrees = sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
                            val x1 = centerX + (azDiff1 * hSensitivity)
                            val y1 = centerY - (elDiff1 * vSensitivity)
                            val x2 = centerX + (azDiff2 * hSensitivity)
                            val y2 = centerY - (elDiff2 * vSensitivity)

                            if ((x1 in 0f..width && y1 in 0f..height) || (x2 in 0f..width && y2 in 0f..height)) {
                                drawLine(
                                    color = ComposeColor.White,
                                    start = Offset(x1, y1),
                                    end = Offset(x2, y2),
                                    strokeWidth = 1.dp.toPx(),
                                    alpha = 0.4f
                                )
                            }
                        }
                    }

                    // 2. Draw Constellation Names
                    sensorData.constellationLabels.forEach { (name, pos) ->
                        val azDiff = (pos.first - sensorData.trueHeading + 540) % 360 - 180
                        val elDiff = pos.second - sensorData.truePitch

                        rotate(degrees = sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
                            val screenX = centerX + (azDiff * hSensitivity)
                            val screenY = centerY - (elDiff * vSensitivity)

                            if (screenX in 0f..width && screenY in 0f..height) {
                                drawContext.canvas.nativeCanvas.drawText(name.uppercase(), screenX, screenY, promptPaint)
                            }
                        }
                    }
                }

                // 3. Draw Celestial Objects
                sensorData.celestialObjects.forEach { obj ->
                    val azDiff = (obj.azimuth - sensorData.trueHeading + 540) % 360 - 180
                    val elDiff = obj.altitude - sensorData.truePitch

                    rotate(degrees = sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
                        val screenX = centerX + (azDiff * hSensitivity)
                        val screenY = centerY - (elDiff * vSensitivity) 

                        if (screenX in 0f..width && screenY in 0f..height) {
                            val color = when (obj.type) {
                                "sun" -> ComposeColor.Yellow
                                "moon" -> ComposeColor.White
                                "planet" -> ComposeColor.Cyan
                                else -> ComposeColor.White
                            }
                            val radius = when (obj.type) {
                                "sun", "moon" -> 8.dp.toPx()
                                "planet" -> 4.dp.toPx()
                                else -> max(1f, 3f - (obj.magnitude / 2f)).dp.toPx()
                            }
                            drawCircle(color = color, radius = radius, center = Offset(screenX, screenY))
                            
                            val label = if (sensorData.showConstellations && obj.type == "star" && obj.bayer.isNotEmpty()) {
                                // Only use Greek letter if star is part of a constellation line
                                val isInConstellation = sensorData.constellationLines.any { it.first.bayer == obj.bayer || it.second.bayer == obj.bayer }
                                if (isInConstellation) {
                                    obj.bayer.split(" ").first() // extract Greek letter
                                } else {
                                    obj.name
                                }
                            } else {
                                obj.name
                            }

                            val rowHeight = 12.dp.toPx()
                            drawContext.canvas.nativeCanvas.drawText(label, screenX, screenY + radius + rowHeight, textPaintWhite)
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
            Text(text = altimeterData.status, color = ComposeColor.Gray, style = MaterialTheme.typography.labelSmall)
            Text(text = "${altimeterData.correctedAltitudeM} m", color = ComposeColor.White, style = MaterialTheme.typography.titleMedium)
            Text(text = "${"%.1f".format(altimeterData.rawPressureHpa)} hPa", color = ComposeColor.White, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "MAG ${sensorData.headingString}", color = ComposeColor.Green, style = MaterialTheme.typography.titleSmall)
            if (sensorData.isManualCalibrated) Text(text = "MAN ${sensorData.manualHeadingString}", color = ComposeColor.Yellow, style = MaterialTheme.typography.titleSmall)
            if (sensorData.isGpsCalibrated) Text(text = "MOV ${sensorData.gpsHeadingString}", color = ComposeColor.White, style = MaterialTheme.typography.titleSmall)
        }

        // 5. Airport Overlay
        if (altimeterData.isDetailVisible) {
            Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = 0.8f)).clickable { onAltimeterClick() }.padding(16.dp), contentAlignment = Alignment.Center) {
                Column(modifier = Modifier.fillMaxWidth().background(ComposeColor.DarkGray.copy(alpha = 0.9f)).padding(16.dp)) {
                    Text(text = "Nearest Airports", color = ComposeColor.White, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    altimeterData.airports.take(5).forEach { airport ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "${airport.code} ${airport.distanceKm.toInt()}km ${airport.pressureHpa.toInt()}hPa ${airport.elevationM.toInt()}m ${convertTime(airport.reportTime)}", color = ComposeColor.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.fillMaxWidth())
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
                 onGroundedToggle = onGroundedToggle,
                 onRadarClick = onRadarClick
             )
         }

        // 7. STARS Mode Overlay (Toggles)
        if (appMode == "STARS") {
            StarsOverlay(
                altimeterSize = altimeterSize.value,
                sensorData = sensorData,
                onConstellationToggle = onConstellationToggle
            )
        }
    }
}

@Composable
fun StarsOverlay(
    altimeterSize: IntSize = IntSize.Zero,
    sensorData: SensorData,
    onConstellationToggle: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (altimeterSize != IntSize.Zero) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 100.dp)
                    .navigationBarsPadding()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy((-10).dp)) {
                    Text("Constellations", color = ComposeColor.White, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(" ", color = ComposeColor.White, style = MaterialTheme.typography.labelSmall)
                        Switch(
                            checked = sensorData.showConstellations,
                            onCheckedChange = { onConstellationToggle() },
                            modifier = Modifier.scale(0.9f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ComposeColor.Yellow,
                                uncheckedThumbColor = ComposeColor.Gray,
                                checkedTrackColor = ComposeColor.DarkGray,
                                uncheckedTrackColor = ComposeColor.Black
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlanesOverlay(
    altimeterSize: IntSize = IntSize.Zero,
    sensorData: SensorData,
    onDistanceChange: (Float) -> Unit,
    onVerboseToggle: () -> Unit,
    onGroundedToggle: () -> Unit,
    onRadarClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (altimeterSize != IntSize.Zero) {
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 100.dp).navigationBarsPadding().width(260.dp).height(250.dp)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.align(Alignment.CenterStart).size(180.dp).background(color = ComposeColor(0, 50, 0), shape = androidx.compose.foundation.shape.CircleShape).clickable { onRadarClick() }) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val radius = size.width / 2
                                val center = Offset(size.width / 2, size.height / 2)
                                val ringRadii = listOf(30.dp.toPx(), 60.dp.toPx(), 90.dp.toPx())
                                ringRadii.forEach { r -> drawCircle(color = ComposeColor.White, radius = r, center = center, style = Stroke(width = 1.dp.toPx()), alpha = 0.5f) }
                                for (angle in 0 until 360 step 30) {
                                    val rad = Math.toRadians(angle.toDouble())
                                    val endX = center.x + radius * cos(rad).toFloat()
                                    val endY = center.y + radius * sin(rad).toFloat()
                                    drawLine(color = ComposeColor.White, start = center, end = Offset(endX, endY), strokeWidth = 1.dp.toPx(), alpha = 0.3f)
                                }
                                val startAngle = sensorData.trueHeading - 90f - 22.5f
                                drawArc(color = ComposeColor(0, 100, 0), startAngle = startAngle, sweepAngle = 45f, useCenter = true, alpha = 0.5f)
                                val mapRadiusKm = sensorData.planesDistance / 2f
                                if (mapRadiusKm > 0) {
                                    sensorData.nearbyAircraft.forEach { aircraft ->
                                        val distRatio = aircraft.distanceKm / mapRadiusKm
                                        if (distRatio <= 1.0f) {
                                            val angleRad = Math.toRadians((aircraft.bearingDegrees - 90f).toDouble())
                                            val x = center.x + (distRatio * radius * cos(angleRad).toFloat())
                                            val y = center.y + (distRatio * radius * sin(angleRad).toFloat())
                                            if (sensorData.showGrounded || aircraft.speedKts >= 20) {
                                                drawCircle(color = ComposeColor.White, radius = 2.dp.toPx(), center = Offset(x, y))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier.align(Alignment.BottomStart).padding(start = 150.dp, bottom = 10.dp).width(80.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(110.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy((-15).dp)) { //do not change
                                Text("Verbose", color = ComposeColor.White, style = MaterialTheme.typography.bodyLarge)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        " ", color = ComposeColor.White, style = MaterialTheme.typography.labelSmall) //do not change
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
                            Column(verticalArrangement = Arrangement.spacedBy((-15).dp)) { //do not change
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(" ", color = ComposeColor.White, style = MaterialTheme.typography.labelSmall) //do not change
                                    Switch(checked = sensorData.showGrounded, onCheckedChange = { onGroundedToggle() }, modifier = Modifier.scale(0.9f), colors = SwitchDefaults.colors(checkedThumbColor = ComposeColor.Green, uncheckedThumbColor = ComposeColor.Gray, checkedTrackColor = ComposeColor.DarkGray, uncheckedTrackColor = ComposeColor.Black))
                                }
                                Text("Ground", color = ComposeColor.White, style = MaterialTheme.typography.bodyMedium)//do not change
                            }
                        }
                        Box(modifier = Modifier.align(Alignment.CenterEnd).width(260.dp).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
                            Column(modifier = Modifier.padding(top = 10.dp, start = 0.dp), horizontalAlignment = Alignment.Start) {
                                Text(text = "${sensorData.planesDistance.toInt()}km", color = ComposeColor.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                val shortSource = when (sensorData.lastAdbSource) { "OpenSky" -> "OS" "ADS-B Exchange" -> "AE" "Airplanes.Live" -> "AL" else -> "N/A" }
                                Text(text = sensorData.lastAdbUpdateTime, color = ComposeColor.Gray, style = MaterialTheme.typography.labelSmall)
                                Text(text = shortSource, color = ComposeColor.Gray, style = MaterialTheme.typography.labelSmall)
                            }
                            Box(modifier = Modifier.fillMaxHeight().width(32.dp).align(Alignment.CenterEnd), contentAlignment = Alignment.Center) {
                                Slider(value = sensorData.planesDistanceValue, onValueChange = onDistanceChange, modifier = Modifier.requiredWidth(250.dp).graphicsLayer { rotationZ = -90f }, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = ComposeColor.Yellow, activeTrackColor = ComposeColor.Gray, inactiveTrackColor = ComposeColor.DarkGray))
                            }
                        }
                    }
                }
        }
    }
}

private fun expandAirline(callsign: String): String {
    if (callsign.length < 3) return callsign
    val icao = callsign.substring(0, 3).uppercase()
    return when (icao) {
        "AAL" -> "American Airlines"
        "BAW" -> "British Airways"
        "DAL" -> "Delta Air Lines"
        "DLH" -> "Lufthansa"
        "FDX" -> "FedEx"
        "JBU" -> "JetBlue Airways"
        "KLM" -> "KLM Royal Dutch"
        "SWA" -> "Southwest Airlines"
        "UAL" -> "United Airlines"
        "UPS" -> "United Parcel Service"
        "AFR" -> "Air France"
        "RYR" -> "Ryanair"
        "EZY" -> "easyJet"
        "WZZ" -> "Wizz Air"
        "THY" -> "Turkish Airlines"
        "SIA" -> "Singapore Airlines"
        "QFA" -> "Qantas"
        "UAE" -> "Emirates"
        "ETD" -> "Etihad Airways"
        "ACA" -> "Air Canada"
        "ASA" -> "Alaska Airlines"
        "NKS" -> "Spirit Airlines"
        "FFT" -> "Frontier Airlines"
        "SKW" -> "SkyWest Airlines"
        "ENY" -> "Envoy Air"
        "PDT" -> "Piedmont Airlines"
        "EDV" -> "Endeavor Air"
        "ASH" -> "Mesa Airlines"
        "GJS" -> "GoJet Airlines"
        "RPA" -> "Republic Airways"
        "CPZ" -> "Compass Airlines"
        "QXE" -> "Horizon Air"
        "SWR" -> "Swiss International"
        "IBE" -> "Iberia"
        "VOO" -> "Volaris"
        "AMX" -> "Aeroméxico"
        "AZA" -> "Alitalia"
        "ANA" -> "All Nippon Airways"
        "CAL" -> "China Airlines"
        "CSA" -> "Czech Airlines"
        "EAL" -> "Eastern Air Lines"
        "FIN" -> "Finnair"
        "HAL" -> "Hawaiian Airlines"
        "JAL" -> "Japan Airlines"
        "KAL" -> "Korean Air"
        "LAN" -> "LATAM Airlines"
        "MAS" -> "Malaysia Airlines"
        "PAL" -> "Philippine Airlines"
        "QTR" -> "Qatar Airways"
        "SAS" -> "Scandinavian Airlines"
        "TAP" -> "TAP Air Portugal"
        "THY" -> "Turkish Airlines"
        "VIR" -> "Virgin Atlantic"
        "VLG" -> "Vueling"
        "WJA" -> "WestJet"
        else -> callsign
    }
}

private fun expandType(type: String): String {
    val upper = type.uppercase()
    return when (upper) {
        "A318" -> "Airbus A318"
        "A319" -> "Airbus A319"
        "A320" -> "Airbus A320"
        "A321" -> "Airbus A321"
        "A332" -> "Airbus A330-200"
        "A333" -> "Airbus A330-300"
        "A343" -> "Airbus A340-300"
        "A346" -> "Airbus A340-600"
        "A359" -> "Airbus A350-900"
        "A35K" -> "Airbus A350-1000"
        "A388" -> "Airbus A380-800"
        "B737" -> "Boeing 737"
        "B738" -> "Boeing 737-800"
        "B739" -> "Boeing 737-900"
        "B38M" -> "Boeing 737 MAX 8"
        "B39M" -> "Boeing 737 MAX 9"
        "B744" -> "Boeing 747-400"
        "B748" -> "Boeing 747-8"
        "B752" -> "Boeing 757-200"
        "B753" -> "Boeing 757-300"
        "B762" -> "Boeing 762"
        "B763" -> "Boeing 763"
        "B764" -> "Boeing 764"
        "B772" -> "Boeing 777-200"
        "B77W" -> "Boeing 777-300ER"
        "B788" -> "Boeing 787-8"
        "B789" -> "Boeing 787-9"
        "B78X" -> "Boeing 787-10"
        "CRJ1" -> "Bombardier CRJ-100"
        "CRJ2" -> "Bombardier CRJ-200"
        "CRJ7" -> "Bombardier CRJ-700"
        "CRJ9" -> "Bombardier CRJ-900"
        "CRJX" -> "Bombardier CRJ-1000"
        "E135" -> "Embraer ERJ-135"
        "E145" -> "Embraer ERJ-145"
        "E170" -> "Embraer 170"
        "E175" -> "Embraer 175"
        "E190" -> "Embraer 190"
        "E195" -> "Embraer 195"
        "E290" -> "Embraer E190-E2"
        "E295" -> "Embraer E195-E2"
        "GLF4" -> "Gulfstream IV"
        "GLF5" -> "Gulfstream V"
        "GLF6" -> "Gulfstream VI"
        "GL5T" -> "Gulfstream G500"
        "G550" -> "Gulfstream G550"
        "G650" -> "Gulfstream G650"
        "BCS1" -> "Airbus A220-100"
        "BCS3" -> "Airbus A220-300"
        "DH8D" -> "Dash 8 Q400"
        "AT72" -> "ATR 72"
        "AT75" -> "ATR 72-500"
        "AT76" -> "ATR 72-600"
        "MD80" -> "McDonnell Douglas MD-80"
        "MD82" -> "McDonnell Douglas MD-82"
        "MD83" -> "McDonnell Douglas MD-83"
        "MD88" -> "McDonnell Douglas MD-88"
        "MD90" -> "McDonnell Douglas MD-90"
        "F900" -> "Falcon 900"
        "FA7X" -> "Falcon 7X"
        "FA8X" -> "Falcon 8X"
        "C510" -> "Cessna Citation Mustang"
        "C525" -> "Cessna CitationJet"
        "C550" -> "Cessna Citation II"
        "C560" -> "Cessna Citation V"
        "C680" -> "Cessna Citation Sovereign"
        "C750" -> "Cessna Citation X"
        "C172" -> "Cessna 172 Skyhawk"
        "C182" -> "Cessna 182 Skylane"
        "C208" -> "Cessna 208 Caravan"
        "SR20" -> "Cirrus SR20"
        "SR22" -> "Cirrus SR22"
        "SF50" -> "Cirrus Vision SF50"
        "PC12" -> "Pilatus PC-12"
        "PC24" -> "Pilatus PC-24"
        "CL30" -> "Challenger 300"
        "CL35" -> "Challenger 350"
        "CL60" -> "Challenger 600"
        "CL64" -> "Challenger 604"
        "CL65" -> "Challenger 605"
        "GLEX" -> "Global Express"
        "GL6T" -> "Global 6000"
        "GL7T" -> "Global 7500"
        else -> type
    }
}
