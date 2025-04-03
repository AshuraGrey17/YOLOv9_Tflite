package com.surendramaran.yolov9tflite

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.surendramaran.yolov9tflite.Constants.LABELS_PATH
import com.surendramaran.yolov9tflite.Constants.MODEL_PATH
import com.surendramaran.yolov9tflite.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService

    // CardView and TextView for heads-up notification
    private lateinit var notificationBanner: CardView
    private lateinit var notificationText: TextView

    // Enum class for severity levels
    enum class Severity(val color: Int) {
        LOW(R.color.yellow),
        MEDIUM(R.color.orange),
        HIGH(R.color.red)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)

        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        bindListeners()

        // Initialize notification components
        notificationBanner = findViewById(R.id.detectionNotificationCard)
        notificationText = findViewById(R.id.detectionNotificationText)

        // Dismiss notification on tap
        notificationBanner.setOnClickListener {
            hideNotification()
        }

        // Floating Action Button for showing bottom dialog
        binding.fab.setOnClickListener { showBottomDialog() }
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                buttonView.setBackgroundColor(
                    ContextCompat.getColor(baseContext, if (isChecked) R.color.orange else R.color.gray)
                )
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(baseContext)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).toTypedArray()
    }

    // Mapping detected objects to severity levels
    private fun getSeverity(detection: String): Severity {
        return when (detection) {
            "Manholes", "Road-cracks" -> Severity.LOW
            "Uneven-terrain", "Speed-Bumps" -> Severity.MEDIUM
            "Unfinished-pavements", "Potholes", "Puddle" -> Severity.HIGH
            else -> Severity.LOW
        }
    }

    fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            if (boundingBoxes.isNotEmpty()) {
                val detectedClass = boundingBoxes[0].clsName
                val severity = getSeverity(detectedClass)
                showNotification("$detectedClass detected! Severity: ${severity.name}")
            } else {
                hideNotification()
            }

            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }

    fun onEmptyDetect() {
        runOnUiThread {
            hideNotification()
        }
    }

    // Show notification with dynamic severity color
    private fun showNotification(detection: String) {
        val detectedClass = detection.split(" ")[0] // Extracts only the detected class name
        val severity = getSeverity(detectedClass) // Now correctly assigns severity

        notificationBanner.setCardBackgroundColor(ContextCompat.getColor(this, severity.color))
        notificationText.text = detection
        notificationBanner.visibility = View.VISIBLE
        notificationBanner.animate().translationY(0f).setDuration(300).start()
    }

    private fun hideNotification() {
        notificationBanner.visibility = View.GONE
    }

    private fun toast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(baseContext, message, Toast.LENGTH_LONG).show()
        }
    }

    // LayoutActivity methods for dialog management
    private fun showBottomDialog() {
        val dialog = createDialog(R.layout.bottomsheetlayout)
        dialog.show()

        val menuButton: FloatingActionButton? = dialog.findViewById(R.id.menuButton)
        val cancelButton: ImageView? = dialog.findViewById(R.id.cancelButton)

        menuButton?.setOnClickListener {
            dialog.dismiss()
            showMenuBottomDialog()
        }

        cancelButton?.setOnClickListener { dialog.dismiss() }
    }

    private fun showMenuBottomDialog() {
        val dialog = createDialog(R.layout.bottomsheet_menu)

        val settingsLayout: LinearLayout? = dialog.findViewById(R.id.layoutSettings)
        val profileLayout: LinearLayout? = dialog.findViewById(R.id.layoutProfile)
        val reportLayout: LinearLayout? = dialog.findViewById(R.id.layoutReport)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        settingsLayout?.setOnClickListener {
            dialog.dismiss()
            showSettingsMenuDialog()
        }

        profileLayout?.setOnClickListener {
            dialog.dismiss()
            showProfileMenuDialog()
        }

        reportLayout?.setOnClickListener {
            dialog.dismiss()
            showReportMenuDialog()
        }

        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showSettingsMenuDialog() {
        val dialog = createDialog(R.layout.settings)

        val alertModeLayout: LinearLayout? = dialog.findViewById(R.id.layoutAlertMode)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        alertModeLayout?.setOnClickListener {
            dialog.dismiss()
            showAlertModeDialog()
        }

        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showAlertModeDialog() {
        val dialog = createDialog(R.layout.settings_alertmode)

        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showProfileMenuDialog() {
        val dialog = createDialog(R.layout.profile)

        val userDetailsLayout: LinearLayout? = dialog.findViewById(R.id.layoutuserdetails)
        val signOutLayout: LinearLayout? = dialog.findViewById(R.id.layoutsignout)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        userDetailsLayout?.setOnClickListener {
            dialog.dismiss()
            showUserDetailsDialog()
        }

        signOutLayout?.setOnClickListener { dialog.dismiss() }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showUserDetailsDialog() {
        val dialog = createDialog(R.layout.profile_userdetails)

        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showReportMenuDialog() {
        val dialog = createDialog(R.layout.report)

        val historyLayout: LinearLayout? = dialog.findViewById(R.id.historyLayout)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        historyLayout?.setOnClickListener {
            dialog.dismiss()
            showSettingsMenuDialog()
        }

        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    fun Alertswitch(view: View) {
        val switch = view as Switch
        val isChecked = switch.isChecked
        Toast.makeText(this, if (isChecked) "Alerts Enabled" else "Alerts Disabled", Toast.LENGTH_SHORT).show()
    }

    fun Notificationsswitch(view: View) {
        val switch = view as Switch
        val isChecked = switch.isChecked
        Toast.makeText(this, if (isChecked) "Notifications Enabled" else "Notifications Disabled", Toast.LENGTH_SHORT).show()
    }

    fun Nightmodeswitch(view: View) {
        val switch = view as Switch
        val isChecked = switch.isChecked
        Toast.makeText(this, if (isChecked) "Night Mode Enabled" else "Night Mode Disabled", Toast.LENGTH_SHORT).show()
    }

    private fun createDialog(layoutResId: Int): Dialog {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(layoutResId)
        }
        setupDialogWindow(dialog)
        return dialog
    }

    private fun setupDialogWindow(dialog: Dialog) {
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes.windowAnimations = R.style.DialogAnimation
            setGravity(Gravity.BOTTOM)
        }
    }
}