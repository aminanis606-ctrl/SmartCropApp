package com.smartcrop.app

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

class FrameProcessor(
    private val faceDetector: FaceDetector?,
    private val cameraSmoother: SpringSmoother
) {

    fun processFrame(bitmap: Bitmap, defaultCenterX: Float): Float {
        if (faceDetector == null) return cameraSmoother.update(defaultCenterX)

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = faceDetector.detect(mpImage)

            if (result.detections().isNotEmpty()) {
                val boundingBox = result.detections()[0].boundingBox()
                // Ambil titik tengah sumbu X dari wajah yang terdeteksi
                val faceCenterX = boundingBox.centerX()
                return cameraSmoother.update(faceCenterX)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Jika wajah tidak terdeteksi, gunakan posisi tengah default yang diredam
        return cameraSmoother.update(defaultCenterX)
    }
}
