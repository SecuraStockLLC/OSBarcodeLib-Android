package com.outsystems.plugins.barcode.controller

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.outsystems.plugins.barcode.controller.helper.OSBARCZXingHelperInterface
import com.outsystems.plugins.barcode.model.OSBARCBoundingBox
import com.outsystems.plugins.barcode.model.OSBARCError
import com.outsystems.plugins.barcode.model.OSBARCScanResult
import kotlin.math.max
import kotlin.math.min

/**
 * Wrapper class that implements the OSBARCScanLibraryInterface
 * to scan an image using the ZXing library.
 */
class OSBARCZXingWrapper(private val helper: OSBARCZXingHelperInterface) : OSBARCScanLibraryInterface {

    companion object {
        private const val LOG_TAG = "OSBARCZXingWrapper"
    }

    /**
     * Scans an image looking for barcodes, using the ZXing library.
     * @param imageProxy - ImageProxy object that represents the image to be analyzed.
     * @param onSuccess - The code to be executed if the operation was successful.
     * @param onError - The code to be executed if the operation was not successful.
     */
    override fun scanBarcode(
        imageProxy: ImageProxy,
        imageBitmap: Bitmap,
        onSuccess: (OSBARCScanResult) -> Unit,
        onError: (OSBARCError) -> Unit
    ) {
        try {
            var resultBitmap = imageBitmap

            // rotate the image if it's in portrait mode (rotation = 90 or 270 degrees)
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                resultBitmap = helper.rotateBitmap(resultBitmap, rotationDegrees)
            }

            // scan image using zxing
            val width = resultBitmap.width
            val height = resultBitmap.height
            val pixels = IntArray(width * height)
            resultBitmap.getPixels(
                pixels,
                0, // first index to write into pixels
                width,
                0, // x coordinate of the first pixel to read
                0, // y coordinate of the first pixel to read
                width,
                height
            )

            helper.decodeImage(pixels, width, height,
                {
                    onSuccess(
                        it.copy(
                            boundingBox = mapBoundingBoxToSourceOrientation(
                                it.boundingBox,
                                rotationDegrees,
                                imageBitmap.width.toFloat(),
                                imageBitmap.height.toFloat()
                            )
                        )
                    )
                },
                {
                    onError(OSBARCError.ZXING_LIBRARY_ERROR)
                }
            )
        } catch (e: Exception) {
            e.message?.let { Log.e(LOG_TAG, it) }
            onError(OSBARCError.ZXING_LIBRARY_ERROR)
        }
    }

    private fun mapBoundingBoxToSourceOrientation(
        boundingBox: OSBARCBoundingBox?,
        rotationDegrees: Int,
        sourceWidth: Float,
        sourceHeight: Float
    ): OSBARCBoundingBox? {
        val box = boundingBox ?: return null
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360

        val transformed = when (normalizedRotation) {
            90 -> OSBARCBoundingBox(
                left = box.top,
                top = sourceHeight - box.right,
                right = box.bottom,
                bottom = sourceHeight - box.left
            )

            270 -> OSBARCBoundingBox(
                left = sourceWidth - box.bottom,
                top = box.left,
                right = sourceWidth - box.top,
                bottom = box.right
            )

            180 -> OSBARCBoundingBox(
                left = sourceWidth - box.right,
                top = sourceHeight - box.bottom,
                right = sourceWidth - box.left,
                bottom = sourceHeight - box.top
            )

            else -> box
        }

        return normalizeAndClampBoundingBox(transformed, sourceWidth, sourceHeight)
    }

    private fun normalizeAndClampBoundingBox(
        boundingBox: OSBARCBoundingBox,
        sourceWidth: Float,
        sourceHeight: Float
    ): OSBARCBoundingBox? {
        val left = min(boundingBox.left, boundingBox.right).coerceIn(0f, sourceWidth)
        val right = max(boundingBox.left, boundingBox.right).coerceIn(0f, sourceWidth)
        val top = min(boundingBox.top, boundingBox.bottom).coerceIn(0f, sourceHeight)
        val bottom = max(boundingBox.top, boundingBox.bottom).coerceIn(0f, sourceHeight)

        if (right <= left || bottom <= top) {
            return null
        }

        return OSBARCBoundingBox(left, top, right, bottom)
    }

}
