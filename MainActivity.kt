package com.example.smartcropapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupSmartCropEngine()
    }

    private fun setupSmartCropEngine() {
        val cameraSmoother = SpringSmoother(stiffness = 0.15f, damping = 0.8f)
        val rawFaceX = 540f 
        val smoothedX = cameraSmoother.update(rawFaceX)
    }
}

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
