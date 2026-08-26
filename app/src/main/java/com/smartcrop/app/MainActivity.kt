package com.smartcrop.app

import android.os.Bundle
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
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

        // Muat Face Detector dengan model aset lokal
        setupFaceDetector()
    }

    private fun setupFaceDetector() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_detector.task")
                .build()

            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            faceDetector = FaceDetector.createFromOptions(this, options)
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
