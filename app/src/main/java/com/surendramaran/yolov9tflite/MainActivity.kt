package com.surendramaran.yolov9tflite

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.net.Uri
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ToggleButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isNotificationEnabled = true
    private var detector: Detector? = null
    private lateinit var alertSwitch: Switch
    private var reportImageView: ImageView? = null
    private var profileImageView: ImageView? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var notificationBanner: CardView
    private lateinit var notificationText: TextView

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
        val fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener {
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.bottomsheetlayout, null)
            dialog.setContentView(view)

            val mapView = view.findViewById<MapView>(R.id.map)

            dialog.setOnShowListener {
                mapView.postDelayed({
                    val point = GeoPoint(14.5995, 120.9842)
                    mapView.controller.setZoom(16.0)
                    mapView.controller.setCenter(point)
                    Log.d("MapFix", "🌏 Forced center to Manila: $point")
                }, 500)
            }

            dialog.show()
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

        notificationBanner = findViewById(R.id.detectionNotificationCard)
        notificationText = findViewById(R.id.detectionNotificationText)

        notificationBanner.setOnClickListener { hideNotification() }
        binding.fab.setOnClickListener { showBottomDialog() }
    }
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { reportImageView?.setImageURI(it) }
    }

    private val profileImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { profileImageView?.setImageURI(it) }
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
    private var lastDetectionTime = 0L
    private val detectionInterval = 2000 // Adjust time in milliseconds (e.g., 2000ms = 2 seconds)

    fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastDetectionTime < detectionInterval) {
            return // Skip detection if interval hasn't passed
        }

        lastDetectionTime = currentTime

        runOnUiThread {
            if (boundingBoxes.isNotEmpty()) {
                val detectedBox = boundingBoxes[0] // Only process the first detection
                val detectedClass = detectedBox.clsName
                val severity = getSeverity(detectedClass)

                showNotification("$detectedClass detected! Severity: ${severity.name}")

                if (severity == Severity.HIGH) {
                    vibratePhone() // Vibrate for high severity detections
                }

                binding.overlay.apply {
                    setResults(listOf(detectedBox)) // Pass only one bounding box
                    invalidate()
                }
            } else {
                hideNotification()
            }

            binding.inferenceTime.text = "${inferenceTime}ms"
        }
    }

    private fun vibratePhone() {
        if (!isNotificationEnabled) return // 🚨 Stop vibration if notifications are off

        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            val vibrationEffect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(vibrationEffect)
        }
    }
    fun onEmptyDetect() {
        runOnUiThread {
            hideNotification()
        }
    }

    private fun showNotification(detection: String) {
        Log.d("Notification", "isNotificationEnabled = $isNotificationEnabled") // Debugging log

        if (!isNotificationEnabled) {
            Log.d("Notification", "Notification blocked because isNotificationEnabled = false")
            return
        }

        val detectedClass = detection.split(" ")[0]
        val severity = getSeverity(detectedClass)

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
        val mapView = dialog.findViewById<MapView>(R.id.map)
        MapManager.setupMap(this, mapView, 14.5995, 120.9842) // Use your desired lat/lon
        // Access the views from the inflated dialog layout
        val menuButton: FloatingActionButton? = dialog.findViewById(R.id.menuButton)

        menuButton?.setOnClickListener {
            dialog.dismiss()
            showMenuBottomDialog()
        }

    }


    private fun showMenuBottomDialog() {
        val dialog = createDialog(R.layout.bottomsheet_menu)
        val isGpuToggle: ToggleButton = dialog.findViewById(R.id.isGpu)

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
        // Set up the listener for the GPU toggle button
        isGpuToggle.setOnCheckedChangeListener { buttonView: CompoundButton, isChecked: Boolean ->
            cameraExecutor.submit {
                detector?.restart(isGpu = isChecked)
            }

            // Change the background color of the ToggleButton based on the checked state
            val backgroundColor = if (isChecked) {
                ContextCompat.getColor(baseContext, R.color.green) // On color
            } else {
                ContextCompat.getColor(baseContext, R.color.white) // Off color
            }

            // Update the backgroundTint using the appropriate color
            buttonView.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }


    private fun showSettingsMenuDialog() {
        val dialog = createDialog(R.layout.settings)
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val alertModeLayout: LinearLayout? = dialog.findViewById(R.id.layoutAlertMode)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        backButton?.setOnClickListener {
            dialog.dismiss()
            showBottomDialog() // Go back to main menu
        }
        alertModeLayout?.setOnClickListener {
            dialog.dismiss()
            showAlertModeDialog()
        }

        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
    private fun enableAlertMode() {
        detector?.setTargetClasses(listOf("Road-cracks", "Potholes", "Speed-bumps", "Manholes"))
        toast("Alert mode enabled")
    }

    private fun disableAlertMode() {
        detector?.setTargetClasses(emptyList())
        toast("Alert mode disabled")
    }

    private fun showAlertModeDialog() {
        val dialog = createDialog(R.layout.settings_alertmode)
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)

        val roadCrackCheckbox = dialog.findViewById<CheckBox>(R.id.RoadcrackCheckbox)
        val roadPotholeCheckbox = dialog.findViewById<CheckBox>(R.id.RoadpotholeCheckbox)
        val speedBumpCheckbox = dialog.findViewById<CheckBox>(R.id.SpeedbumpCheckbox)
        val roadManholeCheckbox = dialog.findViewById<CheckBox>(R.id.RoadmanholeCheckbox)
        val unfinishedPavementCheckbox = dialog.findViewById<CheckBox>(R.id.UnfinishedpavementCheckbox)
        val puddlesCheckbox = dialog.findViewById<CheckBox>(R.id.puddlesCheckbox)
        val detectButton = dialog.findViewById<Button>(R.id.viewSuggestedButton)

        val currentClasses = detector?.getTargetClasses() ?: emptyList()
        roadCrackCheckbox.isChecked = currentClasses.contains("Road-cracks")
        roadPotholeCheckbox.isChecked = currentClasses.contains("Potholes")
        speedBumpCheckbox.isChecked = currentClasses.contains("Speed-bumps")
        roadManholeCheckbox.isChecked = currentClasses.contains("Manholes")
        unfinishedPavementCheckbox.isChecked = currentClasses.contains("Unfinished pavements")
        puddlesCheckbox.isChecked = currentClasses.contains("Puddle")

        detectButton.setOnClickListener {
            val selectedClasses = mutableListOf<String>()
            if (roadCrackCheckbox.isChecked) selectedClasses.add("Road-cracks")
            if (roadPotholeCheckbox.isChecked) selectedClasses.add("Potholes")
            if (speedBumpCheckbox.isChecked) selectedClasses.add("Speed-bumps")
            if (roadManholeCheckbox.isChecked) selectedClasses.add("Manholes")
            if (unfinishedPavementCheckbox.isChecked) selectedClasses.add("Unfinished pavements")
            if (puddlesCheckbox.isChecked) selectedClasses.add("Puddle")

            detector?.setTargetClasses(selectedClasses)
            dialog.dismiss()
        }

        backButton?.setOnClickListener {
            dialog.dismiss()
            showSettingsMenuDialog()
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showProfileMenuDialog() {
        val dialog = createDialog(R.layout.profile)
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
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
        backButton?.setOnClickListener {
            dialog.dismiss()
            showBottomDialog()
        }
    }

    private fun showUserDetailsDialog() {
        val dialog = createDialog(R.layout.profile_userdetails)
        val backButton: ImageView? = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView? = dialog.findViewById(R.id.cancelMenuButton)
        val userPicture: ImageView? = dialog.findViewById(R.id.UserPicture)
        // Click to change image
        userPicture?.setOnClickListener {
            profileImageView = it as ImageView
            profileImagePickerLauncher.launch("image/*")
        }

        backButton?.setOnClickListener {
            dialog.dismiss()
            showProfileMenuDialog()
        }
        cancelMenuButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showReportMenuDialog() {
        val dialog = createDialog(R.layout.report_hazard)

        // Top buttons
        val backButton: ImageView = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView = dialog.findViewById(R.id.cancelMenuButton)
        val topCenterButton: ImageView = dialog.findViewById(R.id.topCenterButton)

        // Info TextViews
        val hazardTypeText: TextView = dialog.findViewById(R.id.typeofhazard)
        val locationText: TextView = dialog.findViewById(R.id.location)
        val timeText: TextView = dialog.findViewById(R.id.time)
        val dateText: TextView = dialog.findViewById(R.id.date)

        // Image preview
        val reportImage: ImageView = dialog.findViewById(R.id.reportImage)
        reportImageView = reportImage // store reference for result callback

        reportImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // CheckBoxes
        val roadCrackCheckBox: CheckBox = dialog.findViewById(R.id.RoadcrackcheckBox)
        val roadPotholeCheckBox: CheckBox = dialog.findViewById(R.id.RoadpotholeCheckbox)
        val speedBumpCheckBox: CheckBox = dialog.findViewById(R.id.SpeedbumpCheckbox)
        val roadManholeCheckBox: CheckBox = dialog.findViewById(R.id.RoadmanholeCheckbox)
        val unfinishedPavementCheckBox: CheckBox = dialog.findViewById(R.id.UnfinishedpavementCheckbox)

        // Set listeners
        backButton.setOnClickListener {
            dialog.dismiss()
            showBottomDialog()
        }

        cancelMenuButton.setOnClickListener {
            dialog.dismiss()
        }

        val reportButton: Button = dialog.findViewById(R.id.Reportbutton)
        reportButton.setOnClickListener {
            dialog.dismiss()
            showReportVerificationDialog()
        }

        dialog.show()
    }

    private fun showReportVerificationDialog() {
        val dialog = createDialog(R.layout.report_verification)

        // Top Buttons
        val backButton: ImageView = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView = dialog.findViewById(R.id.cancelMenuButton)
        val topCenterButton: ImageView = dialog.findViewById(R.id.topCenterButton)

        // Info Fields
        val roadTypeText: TextView = dialog.findViewById(R.id.Roadtype)
        val locationText: TextView = dialog.findViewById(R.id.location)
        val timeText: TextView = dialog.findViewById(R.id.time)
        val dateText: TextView = dialog.findViewById(R.id.date)

        // Image Preview
        val reportImage: ImageView = dialog.findViewById(R.id.reportImage)

        // Action Buttons
        val checkButton: Button = dialog.findViewById(R.id.checkButton)
        val denyButton: Button = dialog.findViewById(R.id.denyButton)

        // Set button actions
        backButton.setOnClickListener {
            dialog.dismiss()
            showReportMenuDialog()
        }

        cancelMenuButton.setOnClickListener {
            dialog.dismiss()
        }

        topCenterButton.setOnClickListener {
            dialog.dismiss()
        }

        checkButton.setOnClickListener {
            Toast.makeText(dialog.context, "Report Approved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            showReportHistoryDialog()
        }


        denyButton.setOnClickListener {
            Toast.makeText(dialog.context, "Report Denied", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            showReportMenuDialog()
        }

        dialog.show()
    }

    private fun showReportHistoryDialog() {
        val dialog = createDialog(R.layout.report_history)

        // Top buttons
        val backButton: ImageView = dialog.findViewById(R.id.Backbutton)
        val cancelMenuButton: ImageView = dialog.findViewById(R.id.cancelMenuButton)
        val topCenterButton: ImageView = dialog.findViewById(R.id.topCenterButton)

        val viewSuggestedButton: Button = dialog.findViewById(R.id.viewSuggestedButton)

        backButton.setOnClickListener {
            dialog.dismiss()
            showReportVerificationDialog() // go back to verification screen if needed
        }

        cancelMenuButton.setOnClickListener {
            dialog.dismiss()
        }

        topCenterButton.setOnClickListener {
            dialog.dismiss()
        }

        viewSuggestedButton.setOnClickListener {
            Toast.makeText(dialog.context, "Viewing Suggested Reports", Toast.LENGTH_SHORT).show()
            // add logic here if needed
        }

        dialog.show()
    }
    fun Alertswitch(view: View) {
        val switch = view as Switch
        if (switch.isChecked) {
            enableAlertMode() // Method to enable alert mode
        } else {
            disableAlertMode() // Method to disable alert mode
        }
        Toast.makeText(this, if (switch.isChecked) "Alert Mode Enabled" else "Alert Mode Disabled", Toast.LENGTH_SHORT).show()
    }


    fun Notificationsswitch(view: View) {
        val switch = view as Switch
        isNotificationEnabled = switch.isChecked // ✅ Update the flag

        if (!isNotificationEnabled) {
            hideNotification() // Hide any active notifications
        }

        Toast.makeText(
            this,
            if (isNotificationEnabled) "Notifications Enabled" else "Notifications Disabled",
            Toast.LENGTH_SHORT
        ).show()

        Log.d("NotificationSwitch", "isNotificationEnabled = $isNotificationEnabled") // Debugging log
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