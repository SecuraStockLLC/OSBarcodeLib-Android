package com.outsystems.plugins.barcode.controller.helper

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.NotFoundException
import com.google.zxing.Result
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.outsystems.plugins.barcode.model.OSBARCScanResult
import com.outsystems.plugins.barcode.model.OSBARCScannerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OSBARCZXingHelperTests {

    @Test
    fun givenTinyMarginQrWhenUsingQuietZoneRescueSourceThenDecodeSucceeds() {
        val payload = "QR-RESCUE-TEST-123"
        val (pixels, width, height) = encodeBarcodeToPixels(
            payload,
            BarcodeFormat.QR_CODE,
            width = 72,
            height = 72,
            margin = 0
        )

        val rescueSource = OSBARCZXingHelper.createQuietZoneRescueSource(pixels, width, height)
        val decoded = decodeWithZxing(rescueSource, BarcodeFormat.QR_CODE)

        assertEquals(payload, decoded?.text)
    }

    @Test
    fun givenTinyMarginDataMatrixWhenUsingQuietZoneRescueSourceThenDecodeSucceeds() {
        val payload = "DM-RESCUE-TEST-456"
        val (pixels, width, height) = encodeBarcodeToPixels(
            payload,
            BarcodeFormat.DATA_MATRIX,
            width = 72,
            height = 72,
            margin = 0
        )

        val rescueSource = OSBARCZXingHelper.createQuietZoneRescueSource(pixels, width, height)
        val decoded = decodeWithZxing(rescueSource, BarcodeFormat.DATA_MATRIX)

        assertEquals(payload, decoded?.text)
    }

    @Test
    fun givenCode128WhenDecodeImageThenBoundingBoxHasDrawableHeight() {
        val payload = "CODE128-TRACK-789"
        val (pixels, width, height) = encodeBarcodeToPixels(
            payload,
            BarcodeFormat.CODE_128,
            width = 300,
            height = 100,
            margin = 0
        )

        val helper = OSBARCZXingHelper(OSBARCScannerHint.CODE_128)
        var result: OSBARCScanResult? = null

        helper.decodeImage(
            pixels = pixels,
            width = width,
            height = height,
            onSuccess = {
                result = it
            },
            onError = {
                fail("Expected successful CODE_128 decode")
            }
        )

        assertNotNull(result)
        assertEquals(payload, result?.text)
        val boundingBox = result?.boundingBox
        assertNotNull(boundingBox)
        assertTrue((boundingBox!!.bottom - boundingBox.top) >= 9.5f)
    }

    private fun encodeBarcodeToPixels(
        payload: String,
        format: BarcodeFormat,
        width: Int,
        height: Int,
        margin: Int
    ): Triple<IntArray, Int, Int> {
        val hints = mapOf(EncodeHintType.MARGIN to margin)
        val matrix = MultiFormatWriter().encode(payload, format, width, height, hints)
        val pixels = IntArray(matrix.width * matrix.height)

        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                val index = (y * matrix.width) + x
                pixels[index] = if (matrix.get(x, y)) {
                    0xFF000000.toInt()
                } else {
                    0xFFFFFFFF.toInt()
                }
            }
        }

        return Triple(pixels, matrix.width, matrix.height)
    }

    private fun decodeWithZxing(
        source: RGBLuminanceSource,
        format: BarcodeFormat
    ): Result? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to setOf(format),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }

        val attempts = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(HybridBinarizer(source.invert()))
        )

        for (bitmap in attempts) {
            try {
                return reader.decodeWithState(bitmap)
            } catch (_: NotFoundException) {
                reader.reset()
            }
        }

        return null
    }
}
