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

        // 1. Dynamic opaque black overlay
        drawRect(color = Color.Black, alpha = sensorData.overlayAlpha, size = size)

        // 2. Green Bars (Fused Orientation)
        val barThickness = width * 0.05f
        rotate(degrees = -sensorData.roll, pivot = Offset(centerX, centerY)) {
            val sensitivity = height / 90f
            val horizonY = centerY - (sensorData.pitch * sensitivity)
            
            drawRect(color = Color.Green, alpha = 0.20f, topLeft = Offset(0f, horizonY - (barThickness / 2)), size = androidx.compose.ui.geometry.Size(width, barThickness))
            drawRect(color = Color.Green, alpha = 0.20f, topLeft = Offset(centerX - (barThickness / 2), 0f), size = androidx.compose.ui.geometry.Size(barThickness, height))
            drawCircle(color = Color.Yellow, alpha = 0.11f, radius = barThickness, center = Offset(centerX, horizonY))

            // MAG text: Left justified along horizontal green line
            val magText = "MAG: ${sensorData.headingString}"
            val magDrawY = (horizonY - (barThickness / 2) - 5f).coerceIn(20.dp.toPx(), height - 20.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(magText, 10.dp.toPx(), magDrawY, textPaintYellow)
        }

        // 3. White crosshairs (Gyro-based)
        rotate(degrees = -sensorData.trueRoll, pivot = Offset(centerX, centerY)) {
            val sensitivity = height / 90f
            val trueY = centerY - (sensorData.truePitch * sensitivity)
            
            drawLine(color = Color.White, alpha = 0.90f, start = Offset(0f, trueY), end = Offset(width, trueY), strokeWidth = 2.dp.toPx())
            drawLine(color = Color.White, alpha = 0.90f, start = Offset(centerX, 0f), end = Offset(centerX, height), strokeWidth = 2.dp.toPx())
            
            // True HUD: Right justified ABOVE the horizontal line
            if (sensorData.isSunCalibrated) {
                val trueText = "True: ${sensorData.trueHeading.toInt()}°"
                val clampedTrueY = trueY.coerceIn(40.dp.toPx(), height - 40.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(trueText, width - 10.dp.toPx(), clampedTrueY - 5f, textPaintWhite)
            }
        }

        // Calibration Prompts (Small)
        val promptText = when (sensorData.calibrationState) {
            CalibrationState.STATIONARY_WAIT -> "ORIENTATION CAL: PUT PHONE DOWN"
            CalibrationState.CALIBRATED -> if (sensorData.isSunCalibrated) "READY" else "POINT AT SUN & PRESS COMPASS"
            else -> ""
        }
        if (promptText.isNotEmpty()) drawContext.canvas.nativeCanvas.drawText(promptText, centerX, 60.dp.toPx(), promptPaint)
    }
}
