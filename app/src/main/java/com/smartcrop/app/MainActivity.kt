package com.smartcrop.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSmartCropPipeline()
    }

    private fun initSmartCropPipeline() {
        // Inisialisasi peredam pegas untuk kehalusan gerakan framing vertikal (9:16)
        val cameraSmoother = SpringSmoother(stiffness = 0.12f, damping = 0.75f)

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
