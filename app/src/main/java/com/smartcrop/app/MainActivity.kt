package com.smartcrop.app

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var cropRenderer: CropRenderer
    private lateinit var cameraSmoother: SpringSmoother
    private var faceDetector: FaceDetector? = null
    private var frameProcessor: FrameProcessor? = null

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (checkCameraPermission()) {
            initSmartCropPipeline()
        } else {
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initSmartCropPipeline()
            } else {
                Toast.makeText(this, "Izin kamera diperlukan untuk Smart Crop!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initSmartCropPipeline() {
        glSurfaceView = findViewById(R.id.glSurfaceView)
        glSurfaceView.setEGLContextClientVersion(2)

        // Inisialisasi Renderer OpenGL
        cropRenderer = CropRenderer()
        glSurfaceView.setRenderer(cropRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Inisialisasi peredam pegas untuk kehalusan gerakan framing vertikal (9:16)
        cameraSmoother = SpringSmoother(stiffness = 0.12f, damping = 0.75f)

        // Muat Face Detector dengan model aset lokal
        setupFaceDetector()

        // Hubungkan ke FrameProcessor
        frameProcessor = FrameProcessor(faceDetector, cameraSmoother)
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
