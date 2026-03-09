package com.outsystems.plugins.barcode.mocks

import android.graphics.Bitmap
import com.outsystems.plugins.barcode.controller.helper.OSBARCImageHelperInterface
import org.mockito.Mockito

class OSBARCImageHelperMock: OSBARCImageHelperInterface {
    var bitmap: Bitmap = Mockito.mock(Bitmap::class.java)
    var subsetBitmap: Bitmap = Mockito.mock(Bitmap::class.java)

    override fun bitmapFromImageBytes(imageBytes: ByteArray): Bitmap {
        return bitmap
    }

    override fun createSubsetBitmapFromSource(
        source: Bitmap,
        rectLeft: Int,
        rectTop: Int,
        rectWidth: Int,
        rectHeight: Int
    ): Bitmap {
        return subsetBitmap
    }
}
