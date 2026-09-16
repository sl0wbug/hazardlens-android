package com.droidlinkstd.hazardlens.data

import android.graphics.RectF

/**
 * Represents a detected road hazard.
 *
 * @property boundingBox Normalized coordinates (0.0 to 1.0) for left, top, right, and bottom.
 * @property label Classification name of the hazard (e.g., "Pothole", "Speed Breaker").
 * @property confidence Detection confidence score from 0.0 to 1.0.
 */
data class Detection(
    val boundingBox: RectF,
    val label: String,
    val confidence: Float
)
