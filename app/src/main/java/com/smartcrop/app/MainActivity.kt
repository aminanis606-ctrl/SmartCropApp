package com.smartcrop.app

import android.os.Bundle
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var cameraSmoother: SpringSmoother
    private var faceDetector: FaceDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initSmartCropPipeline()
    }

    private fun initSmartCropPipeline() {
        surfaceView = findViewById(R.id.surfaceView)

        // Inisialisasi peredam pegas untuk kehalusan gerakan framing vertikal (9:16)
        cameraSmoother = SpringSmoother(stiffness = 0.12f, damping = 0.75f)

        // Konfigurasi awal Face Detector (Akan dimuat saat runtime)
        setupFaceDetector()
    }

    private fun setupFaceDetector() {
        try {
            // Konfigurasi dasar opsi deteksi wajah MediaPipe lokal
            val optionsBuilder = FaceDetector.FaceDetectorOptions.builder()
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)

            // Catatan: Model file (.task) akan dimuat dari asset direktori pada tahap berikutnya
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * Model Fisika Spring-Damper untuk Eliminasi Guncangan Kamera Virtual (Inertial Smoothing)
 */
class SpringSmoother(private val stiffness: Float, private val damping: Float) {
    private var currentPos = 0f
    private var velocity = 0f

    fun update(targetPos: Float): Float {
        val force = (targetPos - currentPos) * stiffness
        velocity = (velocity + force) * damping
        currentPos += velocity
        return currentPos
    }
}
