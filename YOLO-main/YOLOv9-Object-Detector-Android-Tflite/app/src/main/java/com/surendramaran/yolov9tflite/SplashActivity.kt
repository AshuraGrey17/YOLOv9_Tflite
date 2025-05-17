package com.surendramaran.yolov9tflite

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var progressStatus = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_screen)

        // Find ProgressBar
        progressBar = findViewById(R.id.progressBar)
        progressBar.max = 100  // Set max progress to 100

        // Start Progress Animation
        simulateProgressBar()
    }

    private fun simulateProgressBar() {
        Thread {
            while (progressStatus < 100) {
                progressStatus += 5  // Increase progress
                handler.post {
                    progressBar.progress = progressStatus
                }
                Thread.sleep(100)  // Delay for smooth animation
            }

            // Navigate to MainActivity after loading
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }.start()
    }
}
