package com.smartcrop.app
package com.smartcrop.app

import android.os.Bundle
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var cameraSmoother: SpringSmoother

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initSmartCropPipeline()
    }

    private fun initSmartCropPipeline() {
        surfaceView = findViewById(R.id.surfaceView)

        // Inisialisasi peredam pegas untuk kehalusan gerakan framing vertikal (9:16)
        cameraSmoother = SpringSmoother(stiffness = 0.12f, damping = 0.75f)

        // Simulasi titik koordinat wajah terdeteksi dari frame video (X Center)
        val mockFaceX = 640f
        val targetCroppedX = cameraSmoother.update(mockFaceX)
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
