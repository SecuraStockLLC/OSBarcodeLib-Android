package com.outsystems.plugins.barcode.model

import java.io.Serializable
import com.google.gson.annotations.SerializedName

/**
 * Data class that represents the object with the scan parameters.
 */
data class OSBARCScanParameters(
    @SerializedName("scanInstructions") val scanInstructions: String?,
    @SerializedName("cameraDirection") val cameraDirection: Int?,
    @SerializedName("scanOrientation") val scanOrientation: Int?,
    @SerializedName("scanButton") val scanButton: Boolean,
    @SerializedName("scanText") val scanText: String,
    @SerializedName("hint") val hint: OSBARCScannerHint?,
    @SerializedName("androidScanningLibrary") val androidScanningLibrary: String?,
    @SerializedName("highlightEnabled") val highlightEnabled: Boolean = true,
    @SerializedName("highlightColor") val highlightColor: String = "#00FF00",
    @SerializedName("highlightStrokeWidth") val highlightStrokeWidth: Float = 4f,
    @SerializedName("closeDelay") val closeDelay: Long = 500L,
    @SerializedName("vibrationEnabled") val vibrationEnabled: Boolean = true,
    @SerializedName("vibrationDuration") val vibrationDuration: Long = 100L
) : Serializable