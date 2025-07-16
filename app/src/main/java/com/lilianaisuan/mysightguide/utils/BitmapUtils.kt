package com.lilianaisuan.mysightguide.utils // ✅ Standardized Package

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    if (imageProxy.format != ImageFormat.YUV_420_888) return null

    val yBuffer = imageProxy.planes[0].buffer
    val uBuffer = imageProxy.planes[1].buffer
    val vBuffer = imageProxy.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
    val imageBytes = out.toByteArray()

    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    return bitmap?.let {
        Bitmap.createBitmap(
            it, 0, 0, it.width, it.height,
            Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) },
            true
        )
    }
}