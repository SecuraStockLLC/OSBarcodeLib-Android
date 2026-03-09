package com.outsystems.plugins.barcode.controller

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.outsystems.plugins.barcode.controller.helper.OSBARCImageHelperInterface
import com.outsystems.plugins.barcode.model.OSBARCBoundingBox
import com.outsystems.plugins.barcode.model.OSBARCError
import com.outsystems.plugins.barcode.model.OSBARCScanResult
import com.outsystems.plugins.barcode.view.ui.theme.SizeRatioHeight
import com.outsystems.plugins.barcode.view.ui.theme.SizeRatioWidth
import java.lang.Exception
import kotlin.math.abs
import kotlin.math.max

/**
 * This class is responsible for implementing the ImageAnalysis.Analyzer interface,
 * and overriding its analyze() method to scan for barcodes in the image frames.
 */
class OSBARCBarcodeAnalyzer(
    private val scanLibrary: OSBARCScanLibraryInterface,
    private val imageHelper: OSBARCImageHelperInterface,
    private val onBarcodeScanned: (OSBARCScanResult) -> Unit,
    private val onScanningError: (OSBARCError) -> Unit,
    private val scanLineEnabled: Boolean = false
): ImageAnalysis.Analyzer {

    var isPortrait = true
    var lastCropWidthPx = 0f
        private set
    var lastCropHeightPx = 0f
        private set

    companion object {
        private const val LOG_TAG = "OSBARCBarcodeAnalyzer"
        private const val SCAN_LINE_TOLERANCE_RATIO = 0.08f
        private const val MIN_SCAN_LINE_TOLERANCE_PX = 24f
    }

    /**
     * Overrides the analyze() method from ImageAnalysis.Analyzer,
     * calling the respective implementation of OSBARCScanLibraryInterface
     * to scan for barcodes in the image.
     * @param image - ImageProxy object that represents the image to be analyzed.
     */
    override fun analyze(image: ImageProxy) {
        try {
            val croppedBitmap = cropBitmap(image.toBitmap())
            lastCropWidthPx = croppedBitmap.width.toFloat()
            lastCropHeightPx = croppedBitmap.height.toFloat()
            val centerY = lastCropHeightPx / 2f

            scanLibrary.scanBarcode(
                image,
                croppedBitmap,
                { result ->
                    // If scan line mode is enabled, only process barcodes that cross the center line
                    if (scanLineEnabled && result.boundingBox != null) {
                        if (!matchesScanLine(result.boundingBox, centerY, lastCropHeightPx)) {
                            return@scanBarcode
                        }
                    }
                    onBarcodeScanned(result)
                },
                {
                    onScanningError(it)
                }
            )
        } catch (e: Exception) {
            e.message?.let { Log.e(LOG_TAG, it) }
            onScanningError(OSBARCError.SCANNING_GENERAL_ERROR)
        }
        image.close()
    }

    private fun matchesScanLine(
        boundingBox: OSBARCBoundingBox,
        centerY: Float,
        imageHeight: Float
    ): Boolean {
        val tolerancePx = max(imageHeight * SCAN_LINE_TOLERANCE_RATIO, MIN_SCAN_LINE_TOLERANCE_PX)
        val boxHeight = abs(boundingBox.bottom - boundingBox.top)

        if (boxHeight <= tolerancePx) {
            val boxCenterY = (boundingBox.top + boundingBox.bottom) / 2f
            return abs(boxCenterY - centerY) <= tolerancePx
        }

        return boundingBox.top <= centerY && boundingBox.bottom >= centerY
    }

    /**
     * Creates a cropped bitmap for the region of interest to scan,
     * where the cropped image is approximately the same size as the frame
     * shown in the UI, with some padding.
     * As such, it will be a bit bigger than the rectangle in the UI
     * It uses different ratios depending on the orientation of the device - portrait or landscape.
     * @param bitmap - Bitmap object to crop.
     */
    private fun cropBitmap(bitmap: Bitmap): Bitmap {
        val rectWidth: Int
        val rectHeight: Int

        if (isPortrait) {
            // for portrait, the image is rotated
            rectWidth = (bitmap.height * SizeRatioWidth).toInt()
            rectHeight = rectWidth
        } else {
            rectWidth = (bitmap.width * SizeRatioWidth).toInt()
            rectHeight = (bitmap.height * SizeRatioHeight).toInt()
        }

        val rectLeft = (bitmap.width - rectWidth) / 2
        val rectTop = (bitmap.height - rectHeight) / 2

        return imageHelper.createSubsetBitmapFromSource(
            bitmap,
            rectLeft,
            rectTop,
            rectWidth,
            rectHeight
        )
    }

}
