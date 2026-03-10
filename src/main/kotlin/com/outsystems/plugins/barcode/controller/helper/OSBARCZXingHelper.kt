package com.outsystems.plugins.barcode.controller.helper

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.outsystems.plugins.barcode.model.OSBARCBoundingBox
import com.outsystems.plugins.barcode.model.OSBARCScanResult
import com.outsystems.plugins.barcode.model.OSBARCScannerHint

/**
 * Helper class that implements the OSBARCZXingHelperInterface
 * to scan an image using the ZXing library.
 * It encapsulates all the code related with the ZXing library.
 */
class OSBARCZXingHelper(private val hint: OSBARCScannerHint?): OSBARCZXingHelperInterface {

    companion object {
        private const val LOG_TAG = "OSBARCZXingHelper"
        private const val WHITE_PIXEL = -0x1
        private const val HEAVY_RETRY_EVERY_N_MISSES = 3
        private const val QUIET_ZONE_PADDING_PX = 12
        private const val QUIET_ZONE_UPSCALE_FACTOR = 2
        private const val MIN_BOUNDING_SIDE_PX = 6f
        private const val MIN_ONE_D_BOUNDING_HEIGHT_PX = 10f

        internal fun createQuietZoneRescueSource(
            pixels: IntArray,
            width: Int,
            height: Int,
            paddingPx: Int = QUIET_ZONE_PADDING_PX,
            upscaleFactor: Int = QUIET_ZONE_UPSCALE_FACTOR
        ): RGBLuminanceSource {
            val safePadding = paddingPx.coerceAtLeast(0)
            val safeScale = upscaleFactor.coerceAtLeast(1)
            val paddedWidth = width + (safePadding * 2)
            val paddedHeight = height + (safePadding * 2)
            val rescueWidth = paddedWidth * safeScale
            val rescueHeight = paddedHeight * safeScale

            val rescuePixels = IntArray(rescueWidth * rescueHeight) { WHITE_PIXEL }
            for (targetY in 0 until rescueHeight) {
                val sourceY = (targetY / safeScale) - safePadding
                if (sourceY !in 0 until height) {
                    continue
                }
                val sourceRowOffset = sourceY * width
                val targetRowOffset = targetY * rescueWidth
                for (targetX in 0 until rescueWidth) {
                    val sourceX = (targetX / safeScale) - safePadding
                    if (sourceX in 0 until width) {
                        rescuePixels[targetRowOffset + targetX] = pixels[sourceRowOffset + sourceX]
                    }
                }
            }

            return RGBLuminanceSource(rescueWidth, rescueHeight, rescuePixels)
        }
    }

    private enum class DecodeProfile {
        CODE_128,
        DATA_MATRIX,
        ONE_DIMENSIONAL,
        TWO_DIMENSIONAL,
        GENERAL
    }

    private data class DecodeAttempt(
        val label: String,
        val binaryBitmap: BinaryBitmap
    )

    private var consecutiveMisses: Int = 0

    private val reader: MultiFormatReader by lazy {
        val format = hint.toZXingBarcodeFormat()
        val hints = mutableMapOf<DecodeHintType, Any>()
        if (shouldEnableTryHarder()) {
            hints[DecodeHintType.TRY_HARDER] = true
        }
        if (format != null) {
            hints[DecodeHintType.POSSIBLE_FORMATS] = setOf(format)
        }
        MultiFormatReader().apply {
            setHints(hints)
        }
    }

    /**
     * Rotates a bitmap, provided with the rotation degrees.
     * @param bitmap - Bitmap object to rotate
     * @param rotationDegrees - degrees to rotate the image.
     * @return the resulting bitmap.
     */
    override fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        // create a matrix for rotation
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())

        // actually rotate the image
        return Bitmap.createBitmap(
            bitmap,
            0, // 0 is the x coordinate of the first pixel in source bitmap
            0, // 0 is the y coordinate of the first pixel in source bitmap
            bitmap.width, // number of pixels in each row
            bitmap.height, // number of rows
            matrix, // matrix to be used for rotation
            true // true states that source bitmap should be filtered using matrix (rotation)
        )
    }

    /**
     * Scans an image looking for barcodes, using the ZXing library.
     * @param pixels - IntArray that represents the image to be analyzed.
     * @param onSuccess - The code to be executed if the operation was successful.
     * @param onError - The code to be executed if the operation was not successful.
     */
    override fun decodeImage(
        pixels: IntArray, width: Int, height: Int,
        onSuccess: (OSBARCScanResult) -> Unit,
        onError: () -> Unit
    ) {
        try {
            val source = RGBLuminanceSource(width, height, pixels)
            val result = decodeWithFallbacks(source, pixels, width, height)
            consecutiveMisses = 0
            val decodedFormat = result.barcodeFormat.toOSBARCScannerHint()

            val boundingBox = extractBoundingBox(
                result = result,
                imageWidth = width.toFloat(),
                imageHeight = height.toFloat(),
                format = decodedFormat
            )

            onSuccess(
                OSBARCScanResult(
                    text = result.text,
                    format = decodedFormat,
                    boundingBox = boundingBox
                )
            )
        } catch (e: NotFoundException) {
            // keep trying, no barcode was found in this camera frame
            consecutiveMisses++
            e.message?.let { Log.d(LOG_TAG, it) }
        } catch (e: Exception) {
            e.message?.let { Log.e(LOG_TAG, it) }
            onError()
        }
    }

    private fun decodeWithFallbacks(
        source: RGBLuminanceSource,
        pixels: IntArray,
        width: Int,
        height: Int
    ): Result {
        val profile = resolveDecodeProfile()
        val runHeavyFallback = shouldRunHeavyFallback(profile)
        val attempts = buildDecodeAttempts(
            profile = profile,
            source = source,
            pixels = pixels,
            width = width,
            height = height,
            runHeavyFallback = runHeavyFallback
        )

        for (attempt in attempts) {
            try {
                return reader.decodeWithState(attempt.binaryBitmap)
            } catch (_: NotFoundException) {
                reader.reset()
            }
        }

        throw NotFoundException.getNotFoundInstance()
    }

    private fun buildDecodeAttempts(
        profile: DecodeProfile,
        source: RGBLuminanceSource,
        pixels: IntArray,
        width: Int,
        height: Int,
        runHeavyFallback: Boolean
    ): List<DecodeAttempt> {
        val attempts = mutableListOf<DecodeAttempt>()
        attempts.add(DecodeAttempt("fast_hybrid", toHybridBinaryBitmap(source)))

        when (profile) {
            DecodeProfile.CODE_128 -> return attempts
            DecodeProfile.DATA_MATRIX,
            DecodeProfile.TWO_DIMENSIONAL,
            DecodeProfile.GENERAL -> {
                attempts.add(DecodeAttempt("global_histogram", toGlobalBinaryBitmap(source)))
            }
            DecodeProfile.ONE_DIMENSIONAL -> Unit
        }

        if (!runHeavyFallback) {
            return attempts
        }

        when (profile) {
            DecodeProfile.CODE_128 -> Unit
            DecodeProfile.ONE_DIMENSIONAL -> {
                attempts.add(DecodeAttempt("heavy_global_histogram", toGlobalBinaryBitmap(source)))
                attempts.add(DecodeAttempt("heavy_inverted_hybrid", toHybridBinaryBitmap(source.invert())))
            }
            DecodeProfile.DATA_MATRIX,
            DecodeProfile.TWO_DIMENSIONAL,
            DecodeProfile.GENERAL -> {
                attempts.add(DecodeAttempt("heavy_inverted_hybrid", toHybridBinaryBitmap(source.invert())))
                attempts.add(DecodeAttempt("heavy_inverted_global", toGlobalBinaryBitmap(source.invert())))

                val rescueSource = createQuietZoneRescueSource(pixels, width, height)
                attempts.add(DecodeAttempt("quiet_zone_rescue_hybrid", toHybridBinaryBitmap(rescueSource)))
                attempts.add(DecodeAttempt("quiet_zone_rescue_global", toGlobalBinaryBitmap(rescueSource)))
                attempts.add(
                    DecodeAttempt(
                        "quiet_zone_rescue_inverted_hybrid",
                        toHybridBinaryBitmap(rescueSource.invert())
                    )
                )
            }
        }

        return attempts
    }

    private fun extractBoundingBox(
        result: Result,
        imageWidth: Float,
        imageHeight: Float,
        format: OSBARCScannerHint
    ): OSBARCBoundingBox? {
        val points = result.resultPoints ?: return null
        if (points.isEmpty()) {
            return null
        }

        val xCoords = points.mapNotNull { it?.x }
        val yCoords = points.mapNotNull { it?.y }
        if (xCoords.isEmpty() || yCoords.isEmpty()) {
            return null
        }

        val rawBox = OSBARCBoundingBox(
            left = xCoords.minOrNull() ?: 0f,
            top = yCoords.minOrNull() ?: 0f,
            right = xCoords.maxOrNull() ?: 0f,
            bottom = yCoords.maxOrNull() ?: 0f
        )

        return normalizeAndInflateBoundingBox(rawBox, imageWidth, imageHeight, format)
    }

    private fun normalizeAndInflateBoundingBox(
        boundingBox: OSBARCBoundingBox,
        imageWidth: Float,
        imageHeight: Float,
        format: OSBARCScannerHint
    ): OSBARCBoundingBox? {
        var left = minOf(boundingBox.left, boundingBox.right)
        var right = maxOf(boundingBox.left, boundingBox.right)
        var top = minOf(boundingBox.top, boundingBox.bottom)
        var bottom = maxOf(boundingBox.top, boundingBox.bottom)

        val minWidth = MIN_BOUNDING_SIDE_PX
        val minHeight = if (isOneDimensionalFormat(format)) {
            MIN_ONE_D_BOUNDING_HEIGHT_PX
        } else {
            MIN_BOUNDING_SIDE_PX
        }

        if ((right - left) < minWidth) {
            val centerX = (left + right) / 2f
            val halfWidth = minWidth / 2f
            left = centerX - halfWidth
            right = centerX + halfWidth
        }

        if ((bottom - top) < minHeight) {
            val centerY = (top + bottom) / 2f
            val halfHeight = minHeight / 2f
            top = centerY - halfHeight
            bottom = centerY + halfHeight
        }

        left = left.coerceIn(0f, imageWidth)
        right = right.coerceIn(0f, imageWidth)
        top = top.coerceIn(0f, imageHeight)
        bottom = bottom.coerceIn(0f, imageHeight)

        if (right <= left || bottom <= top) {
            return null
        }

        return OSBARCBoundingBox(left, top, right, bottom)
    }

    private fun shouldEnableTryHarder(): Boolean {
        return when (resolveDecodeProfile()) {
            DecodeProfile.CODE_128,
            DecodeProfile.DATA_MATRIX,
            DecodeProfile.ONE_DIMENSIONAL -> false
            DecodeProfile.TWO_DIMENSIONAL,
            DecodeProfile.GENERAL -> true
        }
    }

    private fun resolveDecodeProfile(): DecodeProfile = when (hint) {
        OSBARCScannerHint.CODE_128 -> DecodeProfile.CODE_128
        OSBARCScannerHint.DATA_MATRIX -> DecodeProfile.DATA_MATRIX
        OSBARCScannerHint.CODABAR,
        OSBARCScannerHint.CODE_39,
        OSBARCScannerHint.CODE_93,
        OSBARCScannerHint.ITF,
        OSBARCScannerHint.EAN_13,
        OSBARCScannerHint.EAN_8,
        OSBARCScannerHint.RSS_14,
        OSBARCScannerHint.RSS_EXPANDED,
        OSBARCScannerHint.UPC_A,
        OSBARCScannerHint.UPC_E,
        OSBARCScannerHint.UPC_EAN_EXTENSION -> DecodeProfile.ONE_DIMENSIONAL
        OSBARCScannerHint.QR_CODE,
        OSBARCScannerHint.AZTEC,
        OSBARCScannerHint.PDF_417,
        OSBARCScannerHint.MAXICODE -> DecodeProfile.TWO_DIMENSIONAL
        OSBARCScannerHint.UNKNOWN,
        null -> DecodeProfile.GENERAL
    }

    private fun shouldRunHeavyFallback(profile: DecodeProfile): Boolean {
        if (profile == DecodeProfile.CODE_128) {
            return false
        }
        return ((consecutiveMisses + 1) % HEAVY_RETRY_EVERY_N_MISSES) == 0
    }

    private fun toHybridBinaryBitmap(source: LuminanceSource): BinaryBitmap {
        return BinaryBitmap(HybridBinarizer(source))
    }

    private fun toGlobalBinaryBitmap(source: LuminanceSource): BinaryBitmap {
        return BinaryBitmap(GlobalHistogramBinarizer(source))
    }

    private fun isOneDimensionalFormat(format: OSBARCScannerHint): Boolean {
        return when (format) {
            OSBARCScannerHint.CODABAR,
            OSBARCScannerHint.CODE_39,
            OSBARCScannerHint.CODE_93,
            OSBARCScannerHint.CODE_128,
            OSBARCScannerHint.ITF,
            OSBARCScannerHint.EAN_13,
            OSBARCScannerHint.EAN_8,
            OSBARCScannerHint.RSS_14,
            OSBARCScannerHint.RSS_EXPANDED,
            OSBARCScannerHint.UPC_A,
            OSBARCScannerHint.UPC_E,
            OSBARCScannerHint.UPC_EAN_EXTENSION -> true
            else -> false
        }
    }

    private fun OSBARCScannerHint?.toZXingBarcodeFormat(): BarcodeFormat? = when (this) {
        OSBARCScannerHint.QR_CODE -> BarcodeFormat.QR_CODE
        OSBARCScannerHint.AZTEC -> BarcodeFormat.AZTEC
        OSBARCScannerHint.CODABAR -> BarcodeFormat.CODABAR
        OSBARCScannerHint.CODE_39 -> BarcodeFormat.CODE_39
        OSBARCScannerHint.CODE_93 -> BarcodeFormat.CODE_93
        OSBARCScannerHint.CODE_128 -> BarcodeFormat.CODE_128
        OSBARCScannerHint.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
        OSBARCScannerHint.MAXICODE -> BarcodeFormat.MAXICODE
        OSBARCScannerHint.ITF -> BarcodeFormat.ITF
        OSBARCScannerHint.EAN_13 -> BarcodeFormat.EAN_13
        OSBARCScannerHint.EAN_8 -> BarcodeFormat.EAN_8
        OSBARCScannerHint.PDF_417 -> BarcodeFormat.PDF_417
        OSBARCScannerHint.RSS_14 -> BarcodeFormat.RSS_14
        OSBARCScannerHint.RSS_EXPANDED -> BarcodeFormat.RSS_EXPANDED
        OSBARCScannerHint.UPC_A -> BarcodeFormat.UPC_A
        OSBARCScannerHint.UPC_E -> BarcodeFormat.UPC_E
        OSBARCScannerHint.UPC_EAN_EXTENSION -> BarcodeFormat.UPC_EAN_EXTENSION
        OSBARCScannerHint.UNKNOWN -> null
        null -> null
    }

    private fun BarcodeFormat?.toOSBARCScannerHint(): OSBARCScannerHint = when (this) {
        BarcodeFormat.QR_CODE -> OSBARCScannerHint.QR_CODE
        BarcodeFormat.AZTEC -> OSBARCScannerHint.AZTEC
        BarcodeFormat.CODABAR -> OSBARCScannerHint.CODABAR
        BarcodeFormat.CODE_39 -> OSBARCScannerHint.CODE_39
        BarcodeFormat.CODE_93 -> OSBARCScannerHint.CODE_93
        BarcodeFormat.CODE_128 -> OSBARCScannerHint.CODE_128
        BarcodeFormat.DATA_MATRIX -> OSBARCScannerHint.DATA_MATRIX
        BarcodeFormat.MAXICODE -> OSBARCScannerHint.MAXICODE
        BarcodeFormat.ITF -> OSBARCScannerHint.ITF
        BarcodeFormat.EAN_13 -> OSBARCScannerHint.EAN_13
        BarcodeFormat.EAN_8 -> OSBARCScannerHint.EAN_8
        BarcodeFormat.PDF_417 -> OSBARCScannerHint.PDF_417
        BarcodeFormat.RSS_14 -> OSBARCScannerHint.RSS_14
        BarcodeFormat.RSS_EXPANDED -> OSBARCScannerHint.RSS_EXPANDED
        BarcodeFormat.UPC_A -> OSBARCScannerHint.UPC_A
        BarcodeFormat.UPC_E -> OSBARCScannerHint.UPC_E
        BarcodeFormat.UPC_EAN_EXTENSION -> OSBARCScannerHint.UPC_EAN_EXTENSION

        else -> OSBARCScannerHint.UNKNOWN
    }
}
