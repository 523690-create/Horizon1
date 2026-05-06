package com.example.horizon2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.horizon2.CalibrationState
import com.example.horizon2.SensorData
import com.example.horizon2.AltimeterData
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs

@Composable
fun OverlayView(
    sensorData: SensorData,
    altimeterData: AltimeterData = AltimeterData(),
    onAltimeterClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textPaintYellow = remember(density) {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            alpha = (255 * 0.50f).toInt()
            textSize = with(density) { 20.dp.toPx() }
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
    }

    val textPaintWhite = remember(density) {
        Paint().apply {
            color = android.graphics.Color.WHITE
            alpha = (255 * 0.90f).toInt()
            textSize = with(density) { 18.dp.toPx() }
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
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
                            val tickY = centerY + (delta * sensitivity)
                            if (tickY in -height..height * 2) {
                                drawLine(color = ComposeColor.White, alpha = 0.90f, start = Offset(centerX - 10.dp.toPx(), tickY), end = Offset(centerX + 10.dp.toPx(), tickY), strokeWidth = 2.dp.toPx())
                                
                                rotate(degrees = -sensorData.trueRoll, pivot = Offset(centerX + 60.dp.toPx(), tickY + 5.dp.toPx())) {
                                    val label = if (pitch > 90) 180 - pitch else if (pitch < -90) -180 - pitch else pitch
                                    drawContext.canvas.nativeCanvas.drawText("${label.toInt()}", centerX + 60.dp.toPx(), tickY + 5.dp.toPx(), textPaintWhite)
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
        }

        // 4. Altimeter HUD (Top Left)
        Column(
            modifier = Modifier
                .padding(16.dp)
                .statusBarsPadding()
                .clickable { onAltimeterClick() }
        ) {
            Text(
                text = "${String.format("%.1f", altimeterData.rawPressureHpa)} hPa",
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
                    altimeterData.airports.forEach { airport ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${airport.code} ${airport.distanceKm.toInt()}km ${airport.pressureHpa.toInt()}hPa ${airport.elevationM.toInt()}m ${airport.reportTime}",
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
    }
}




