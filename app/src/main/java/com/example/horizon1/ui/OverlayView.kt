package com.example.horizon1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.horizon1.CalibrationState
import com.example.horizon1.SensorData
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos

@Composable
fun OverlayView(
    sensorData: SensorData,
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
            textSize = with(density) { 16.dp.toPx() } // Much smaller as requested
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerX = width / 2
        val centerY = height / 2
        
        val vFov = 60f // Assumed Vertical FOV for matching camera optics
        val sensitivity = height / vFov

        // 1. Dynamic opaque black overlay
        drawRect(color = Color.Black, alpha = sensorData.overlayAlpha, size = size)

        // 2. Green Bars (Fused Orientation)
        val barThickness = width * 0.05f
        // Correct roll sign: tilt left = rotate clockwise.
        // Pitch: positive up = horizon moves down.
        rotate(degrees = sensorData.roll, pivot = Offset(centerX, centerY)) {
            val horizonY = centerY + (sensorData.pitch * sensitivity)
            
            drawRect(color = Color.Green, alpha = 0.20f, topLeft = Offset(-width * 2, horizonY - (barThickness / 2)), size = androidx.compose.ui.geometry.Size(width * 5, barThickness))
            drawRect(color = Color.Green, alpha = 0.20f, topLeft = Offset(centerX - (barThickness / 2), -height * 2), size = androidx.compose.ui.geometry.Size(barThickness, height * 5))
            drawCircle(color = Color.Yellow, alpha = 0.11f, radius = barThickness, center = Offset(centerX, horizonY))

            // MAG text: Left justified along horizontal green line
            val magText = "MAG: ${sensorData.headingString}"
            val magDrawY = (horizonY - (barThickness / 2) - 5f).coerceIn(20.dp.toPx(), height - 20.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(magText, 10.dp.toPx(), magDrawY, textPaintYellow)
        }

        // 3. White HUD (Gyro-based)
        if (sensorData.hasBeenCalibrated) {
            rotate(degrees = sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
                val trueY = centerY + (sensorData.truePitch * sensitivity)
                
                // Horizontal line (Full 360 degree representation)
                drawLine(color = Color.White, alpha = 0.90f, start = Offset(-width * 2, trueY), end = Offset(width * 5, trueY), strokeWidth = 2.dp.toPx())
                
                // Horizontal Ticks (every 10 degrees)
                for (angle in 0 until 360 step 10) {
                    val delta = (angle - sensorData.trueHeading + 540) % 360 - 180
                    val tickX = centerX + (delta * sensitivity)
                    if (tickX in -width..width * 2) {
                        drawLine(color = Color.White, alpha = 0.90f, start = Offset(tickX, trueY - 10.dp.toPx()), end = Offset(tickX, trueY + 10.dp.toPx()), strokeWidth = 2.dp.toPx())
                        
                        rotate(degrees = -sensorData.trueRoll, pivot = Offset(tickX, trueY - 15.dp.toPx())) {
                            drawContext.canvas.nativeCanvas.drawText("$angle", tickX, trueY - 15.dp.toPx(), promptPaint)
                        }
                    }
                }

                // Vertical line
                drawLine(color = Color.White, alpha = 0.90f, start = Offset(centerX, -height * 2), end = Offset(centerX, height * 5), strokeWidth = 2.dp.toPx())
                
                // Persistent Center Bearing: Left of the vertical axis
                rotate(degrees = -sensorData.trueRoll, pivot = Offset(centerX - 15.dp.toPx(), centerY)) {
                    val paint = Paint(textPaintWhite).apply { 
                        textAlign = Paint.Align.RIGHT 
                        textSize = with(density) { 20.dp.toPx() }
                    }
                    drawContext.canvas.nativeCanvas.drawText(sensorData.trueHeadingString, centerX - 15.dp.toPx(), centerY + 7.dp.toPx(), paint)
                }

                // Vertical Ticks (every 15 degrees)
                for (pitch in -90..90 step 15) {
                    val delta = pitch - sensorData.truePitch
                    val tickY = centerY - (delta * sensitivity)
                    if (tickY in -height..height * 2) {
                        drawLine(color = Color.White, alpha = 0.90f, start = Offset(centerX - 10.dp.toPx(), tickY), end = Offset(centerX + 10.dp.toPx(), tickY), strokeWidth = 2.dp.toPx())
                        
                        rotate(degrees = -sensorData.trueRoll, pivot = Offset(centerX + 60.dp.toPx(), tickY + 5.dp.toPx())) {
                            drawContext.canvas.nativeCanvas.drawText("$pitch", centerX + 60.dp.toPx(), tickY + 5.dp.toPx(), textPaintWhite)
                        }
                    }
                }
                
                // True HUD Labels: 
                // GPS above white line, Manual below. Display all available, prioritize recent.
                val manualRecent = sensorData.manualCalibrationTime > sensorData.gpsCalibrationTime
                val gpsAlpha = if (!manualRecent || !sensorData.isManualCalibrated) 0.90f else 0.50f
                val manualAlpha = if (manualRecent || !sensorData.isGpsCalibrated) 0.90f else 0.50f

                if (sensorData.isGpsCalibrated) {
                    val trueText = "True (GPS): ${sensorData.gpsHeadingString}"
                    val paint = Paint(textPaintWhite).apply { alpha = (255 * gpsAlpha).toInt() }
                    drawContext.canvas.nativeCanvas.drawText(trueText, width - 10.dp.toPx(), trueY - 15.dp.toPx(), paint)
                }
                if (sensorData.isManualCalibrated) {
                    val trueText = "True (manual): ${sensorData.manualHeadingString}"
                    val paint = Paint(textPaintWhite).apply { alpha = (255 * manualAlpha).toInt() }
                    drawContext.canvas.nativeCanvas.drawText(trueText, width - 10.dp.toPx(), trueY + 25.dp.toPx(), paint)
                }
            }
        }

        // Calibration Prompts (Small)
        val promptText = when {
            !sensorData.hasBeenCalibrated -> "KEEP DEVICE STILL TO CALIBRATE"
            !sensorData.isGpsCalibrated && !sensorData.isManualCalibrated -> "MOVE TO GPS CALIBRATE OR USE MANUAL"
            else -> "READY"
        }
        if (promptText.isNotEmpty()) drawContext.canvas.nativeCanvas.drawText(promptText, centerX, 60.dp.toPx(), promptPaint)
    }
}
