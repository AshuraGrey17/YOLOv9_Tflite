package com.surendramaran.yolov9tflite

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingText: TextView
    private lateinit var tagline: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var progress = 0
    private val tips = listOf(
        "Analyzing objects...",
        "Preparing the Application...",
        "Calibrating sensors...",
        "Verifying road safety..."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_screen)

        // View bindings
        progressBar = findViewById(R.id.progressBar)
        loadingText = findViewById(R.id.loadingText)
        tagline = findViewById(R.id.tagline)
        val appLogo = findViewById<ImageView>(R.id.appLogo)

        progressBar.max = 100

        // Animate logo
        appLogo.scaleX = 0.8f
        appLogo.scaleY = 0.8f
        appLogo.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(1f)
            .setDuration(1000)
            .start()

        // Animate tagline and loading text
        tagline.animate().alpha(1f).setStartDelay(500).setDuration(800).start()
        loadingText.animate().alpha(1f).setStartDelay(1000).setDuration(800).start()

        // Start animations
        showTips()
        startSmoothProgress()
    }

    private fun startSmoothProgress() {
        handler.post(object : Runnable {
            override fun run() {
                if (progress < 100) {
                    progress++
                    progressBar.progress = progress
                    handler.postDelayed(this, 60) // Smooth interval (~6s total)
                } else {
                    // Finish and go to MainActivity
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                }
            }
        })
    }

    private fun showTips() {
        for (i in tips.indices) {
            handler.postDelayed({
                loadingText.text = tips[i]
            }, (i * 1500).toLong())
        }
    }
}
