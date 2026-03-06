package com.outsystems.plugins.barcode.model

import java.io.Serializable

data class OSBARCBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
): Serializable
