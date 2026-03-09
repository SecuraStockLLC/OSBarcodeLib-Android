package com.outsystems.plugins.barcode.view

import com.outsystems.plugins.barcode.model.OSBARCBoundingBox
import com.outsystems.plugins.barcode.model.OSBARCScanResult
import com.outsystems.plugins.barcode.model.OSBARCScannerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OSBARCScannerActivityTrackingTests {

    @Test
    fun givenScanResultWhenBuildScanLockKeyThenUsesFormatAndText() {
        val result = OSBARCScanResult("ABC-123", OSBARCScannerHint.CODE_128)

        val key = OSBARCScannerActivity.buildScanLockKey(result)

        assertEquals("CODE_128|ABC-123", key)
    }

    @Test
    fun givenSameFormatAndTextWhenIsScanLockMatchThenTrue() {
        val result = OSBARCScanResult("LOCK-ME", OSBARCScannerHint.QR_CODE)
        val key = OSBARCScannerActivity.buildScanLockKey(result)

        assertTrue(OSBARCScannerActivity.isScanLockMatch(result, key))
    }

    @Test
    fun givenDifferentFormatWhenIsScanLockMatchThenFalse() {
        val lockedResult = OSBARCScanResult("LOCK-ME", OSBARCScannerHint.QR_CODE)
        val incomingResult = OSBARCScanResult("LOCK-ME", OSBARCScannerHint.CODE_128)
        val key = OSBARCScannerActivity.buildScanLockKey(lockedResult)

        assertFalse(OSBARCScannerActivity.isScanLockMatch(incomingResult, key))
    }

    @Test
    fun givenCode128FormatWhenIsOneDimensionalFormatThenTrue() {
        assertTrue(OSBARCScannerActivity.isOneDimensionalFormat(OSBARCScannerHint.CODE_128))
    }

    @Test
    fun givenQrFormatWhenIsOneDimensionalFormatThenFalse() {
        assertFalse(OSBARCScannerActivity.isOneDimensionalFormat(OSBARCScannerHint.QR_CODE))
    }

    @Test
    fun givenBoundingBoxWhenWidenOneDimensionalBoxThenCenterPreservedAndWidthExpanded() {
        val original = OSBARCBoundingBox(left = 10f, top = 20f, right = 110f, bottom = 40f)

        val widened = OSBARCScannerActivity.widenOneDimensionalBox(original, widthScale = 1.2f)

        assertEquals(0f, widened.left, 0.001f)
        assertEquals(120f, widened.right, 0.001f)
        assertEquals(original.top, widened.top, 0.001f)
        assertEquals(original.bottom, widened.bottom, 0.001f)
    }
}

