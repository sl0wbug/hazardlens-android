package com.droidlinkstd.hazardlens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.droidlinkstd.hazardlens.data.Detection
import com.droidlinkstd.hazardlens.databinding.ActivityMainBinding
import com.droidlinkstd.hazardlens.detector.HazardDetector
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HazardLensMainActivity"
        private const val MODE_CAMERA = 0
        private const val MODE_IMAGE = 1
        private const val MODE_VIDEO = 2
    }

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var camera: Camera? = null
    private var isFlashOn = false
    private var currentMode = MODE_CAMERA
    private var hasSelectedImage = false
    private var hasSelectedVideo = false
    private var isCameraActive = false

    // Hazard Detector & Async Worker
    private var detector: HazardDetector? = null
    private val detectorExecutor = Executors.newSingleThreadExecutor()
    private var currentImageUri: Uri? = null

    // Video Frame Analysis Support
    private var videoRetriever: MediaMetadataRetriever? = null
    private val videoHandler = Handler(Looper.getMainLooper())
    private val videoFrameIntervalMs = 250L

    // Android Photo & Video Picker (PhotoPicker API)
    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedMedia(uri)
        } else {
            Log.d(TAG, "No visual media selected")
        }
    }

    // Camera Permission Launcher
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCameraPreview()
        } else {
            isCameraActive = false
            binding.tvStatus.text = getString(R.string.status_no_permission)
            if (currentMode == MODE_CAMERA) {
                binding.previewView.isVisible = false
                binding.btnStopCamera.isVisible = false
                binding.btnFlashToggle.isVisible = false
                binding.layoutPlaceholder.isVisible = true
            }
        }
    }

    private val videoAnalysisRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer
            val currentDetector = detector
            if (currentMode == MODE_VIDEO && player != null && player.isPlaying && currentDetector != null && currentDetector.isLoaded) {
                val currentPositionUs = player.currentPosition * 1000L
                executeOnDetector {
                    try {
                        val frame = videoRetriever?.getFrameAtTime(
                            currentPositionUs,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                        if (frame != null) {
                            val frameW = frame.width
                            val frameH = frame.height
                            val result = currentDetector.detect(frame, binding.overlayView.minConfidence)
                            frame.recycle()
                            runOnUiThread {
                                if (currentMode == MODE_VIDEO && player.isPlaying && !isFinishing && !isDestroyed) {
                                    binding.overlayView.sourceWidth = frameW
                                    binding.overlayView.sourceHeight = frameH
                                    updateDetectionUI(result.detections, result.latencyMs)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Video frame detection error", e)
                    }
                }
            }
            if (currentMode == MODE_VIDEO && hasSelectedVideo) {
                videoHandler.postDelayed(this, videoFrameIntervalMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        detector = HazardDetector(this)

        setupTabNavigation()
        setupActionButtons()
        setupSensitivityControls()
        updateModelStatusUI()

        // Default to Camera mode (shows "Open Camera" placeholder)
        updateDetectionMode(MODE_CAMERA)
    }

    private fun updateModelStatusUI() {
        val det = detector
        if (det != null && det.isLoaded) {
            val modelName = det.modelFileName ?: "model.tflite"
            binding.tvSubtitle.text = "Loaded: $modelName • Classes (${det.labels.size}): ${det.labels.joinToString(", ")}"
            binding.tvStatus.text = "Model: Ready"
        } else {
            binding.tvSubtitle.text = "No .tflite model found in assets. Add your model to app/src/main/assets/"
            binding.tvStatus.text = "Model: Awaiting .tflite"
        }
    }

    private fun setupTabNavigation() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                updateDetectionMode(tab?.position ?: MODE_CAMERA)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupSensitivityControls() {
        binding.overlayView.minConfidence = 0.25f
        binding.toggleSensitivity.check(R.id.btnRecall)

        binding.toggleSensitivity.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnRecall -> {
                        binding.overlayView.minConfidence = 0.25f
                    }
                    R.id.btnBalanced -> {
                        binding.overlayView.minConfidence = 0.45f
                    }
                    R.id.btnStrict -> {
                        binding.overlayView.minConfidence = 0.65f
                    }
                }
                if (currentMode == MODE_IMAGE && currentImageUri != null) {
                    processStaticImage(currentImageUri!!)
                }
            }
        }
    }

    private fun setupActionButtons() {
        binding.btnPlaceholderSelect.setOnClickListener {
            if (currentMode == MODE_CAMERA) {
                startCameraMode()
            } else {
                launchMediaPicker()
            }
        }

        binding.layoutPlaceholder.setOnClickListener {
            if (currentMode == MODE_CAMERA) {
                startCameraMode()
            } else {
                launchMediaPicker()
            }
        }

        binding.btnStopCamera.setOnClickListener {
            stopCameraMode()
        }

        binding.btnFlashToggle.setOnClickListener {
            toggleFlashlight()
        }

        binding.btnChangeMedia.setOnClickListener {
            launchMediaPicker()
        }

        binding.btnInfoSpecs.setOnClickListener {
            showArchitectureSpecsDialog()
        }

        binding.imageView.setOnClickListener {
            launchMediaPicker()
        }
    }

    private fun showArchitectureSpecsDialog() {
        val det = detector
        val modelStatus = if (det != null && det.isLoaded) {
            "Active (${det.modelFileName})"
        } else {
            "Pending (place your .tflite in app/src/main/assets/)"
        }
        val classesStatus = det?.labels?.joinToString(", ") ?: "Pothole, Speed Breaker, Manhole, Road Crack"

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_specs_title))
            .setMessage(
                """
                • System Architecture:
                   On-device Mobile Edge Computer Vision (Zero Cloud Dependency for Highway Reliability)
                
                • Model Status:
                   $modelStatus
                
                • Targeted Domain & Classes:
                   $classesStatus
                
                • Vision Execution Pipeline:
                   - Ingestion: CameraX ImageAnalysis (RGBA_8888)
                   - Preprocessing: Letterbox 640x640 Normalization
                   - Core Model: Ultralytics YOLO (YOLOv8 / YOLOv11) converted to TFLite
                   - Output: Non-Maximum Suppression HUD & Warning Banner
                """.trimIndent()
            )
            .setPositiveButton("Understood", null)
            .show()
    }

    private fun toggleFlashlight() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            return
        }
        isFlashOn = !isFlashOn
        cam.cameraControl.enableTorch(isFlashOn)
        binding.btnFlashToggle.setIconTintResource(
            if (isFlashOn) R.color.hazard_amber else R.color.white
        )
    }

    private fun startCameraMode() {
        isCameraActive = true
        binding.layoutPlaceholder.isVisible = false
        binding.previewView.isVisible = true
        binding.btnStopCamera.isVisible = true
        binding.overlayView.isVisible = true
        binding.overlayView.sourceWidth = 0
        binding.overlayView.sourceHeight = 0
        checkCameraPermissionAndStart()
    }

    private fun stopCameraMode() {
        isCameraActive = false
        if (isFlashOn) {
            camera?.cameraControl?.enableTorch(false)
            isFlashOn = false
            binding.btnFlashToggle.setIconTintResource(R.color.white)
        }
        binding.btnFlashToggle.isVisible = false
        camera = null

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            if (cameraProviderFuture.isDone) {
                cameraProviderFuture.get().unbindAll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera", e)
        }

        binding.overlayView.detections = emptyList()
        binding.overlayView.sourceWidth = 0
        binding.overlayView.sourceHeight = 0
        binding.layoutHazardAlert.isVisible = false

        if (currentMode == MODE_CAMERA) {
            binding.previewView.isVisible = false
            binding.btnStopCamera.isVisible = false
            binding.layoutPlaceholder.isVisible = true
            binding.ivPlaceholderIcon.setImageResource(android.R.drawable.ic_menu_camera)
            binding.tvPlaceholderTitle.text = getString(R.string.placeholder_camera_title)
            binding.tvPlaceholderDesc.text = getString(R.string.placeholder_camera_desc)
            binding.btnPlaceholderSelect.text = getString(R.string.action_open_camera)
            binding.overlayView.isVisible = false
            binding.tvStatus.text = getString(R.string.status_idle)
            binding.tvDetected.text = "Detected: None"
            binding.tvLatency.text = "Latency: --"
        }
    }

    private fun launchMediaPicker() {
        when (currentMode) {
            MODE_IMAGE -> {
                pickVisualMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
            MODE_VIDEO -> {
                pickVisualMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            }
        }
    }

    private fun updateDetectionMode(mode: Int) {
        currentMode = mode
        stopVideoAnalysis()

        when (mode) {
            MODE_CAMERA -> {
                pausePlayer()
                binding.btnChangeMedia.isVisible = false

                if (isCameraActive) {
                    binding.previewView.isVisible = true
                    binding.layoutPlaceholder.isVisible = false
                    binding.btnStopCamera.isVisible = true
                    binding.overlayView.isVisible = true
                    checkCameraPermissionAndStart()
                    binding.tvStatus.text = getString(R.string.status_camera_active)
                } else {
                    binding.previewView.isVisible = false
                    binding.playerView.isVisible = false
                    binding.imageView.isVisible = false
                    binding.btnStopCamera.isVisible = false
                    binding.btnFlashToggle.isVisible = false
                    binding.layoutPlaceholder.isVisible = true
                    binding.overlayView.isVisible = false
                    binding.ivPlaceholderIcon.setImageResource(android.R.drawable.ic_menu_camera)
                    binding.tvPlaceholderTitle.text = getString(R.string.placeholder_camera_title)
                    binding.tvPlaceholderDesc.text = getString(R.string.placeholder_camera_desc)
                    binding.btnPlaceholderSelect.text = getString(R.string.action_open_camera)
                    binding.tvStatus.text = getString(R.string.status_idle)
                }
            }
            MODE_IMAGE -> {
                stopCameraMode()
                pausePlayer()
                binding.btnStopCamera.isVisible = false
                binding.btnFlashToggle.isVisible = false
                binding.previewView.isVisible = false
                binding.playerView.isVisible = false

                if (hasSelectedImage && currentImageUri != null) {
                    binding.imageView.isVisible = true
                    binding.layoutPlaceholder.isVisible = false
                    binding.overlayView.isVisible = true
                    binding.btnChangeMedia.isVisible = true
                    processStaticImage(currentImageUri!!)
                } else {
                    binding.imageView.isVisible = false
                    binding.layoutPlaceholder.isVisible = true
                    binding.overlayView.isVisible = false
                    binding.btnChangeMedia.isVisible = false
                    binding.ivPlaceholderIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                    binding.tvPlaceholderTitle.text = getString(R.string.placeholder_image_title)
                    binding.tvPlaceholderDesc.text = getString(R.string.placeholder_image_desc)
                    binding.btnPlaceholderSelect.text = getString(R.string.action_choose_image)
                    binding.tvStatus.text = "Status: Ready to Import Image"
                }
            }
            MODE_VIDEO -> {
                stopCameraMode()
                binding.btnStopCamera.isVisible = false
                binding.btnFlashToggle.isVisible = false
                binding.previewView.isVisible = false
                binding.imageView.isVisible = false

                if (hasSelectedVideo) {
                    binding.playerView.isVisible = true
                    binding.layoutPlaceholder.isVisible = false
                    binding.overlayView.isVisible = true
                    binding.btnChangeMedia.isVisible = true
                    exoPlayer?.play()
                    startVideoAnalysis()
                    binding.tvStatus.text = getString(R.string.status_video_playing)
                } else {
                    binding.playerView.isVisible = false
                    binding.layoutPlaceholder.isVisible = true
                    binding.overlayView.isVisible = false
                    binding.btnChangeMedia.isVisible = false
                    binding.ivPlaceholderIcon.setImageResource(android.R.drawable.ic_media_play)
                    binding.tvPlaceholderTitle.text = getString(R.string.placeholder_video_title)
                    binding.tvPlaceholderDesc.text = getString(R.string.placeholder_video_desc)
                    binding.btnPlaceholderSelect.text = getString(R.string.action_choose_video)
                    binding.tvStatus.text = "Status: Ready to Import Video"
                }
            }
        }
    }

    private fun handleSelectedMedia(uri: Uri) {
        when (currentMode) {
            MODE_IMAGE -> {
                hasSelectedImage = true
                currentImageUri = uri
                binding.layoutPlaceholder.isVisible = false
                binding.imageView.isVisible = true
                binding.overlayView.isVisible = true
                binding.btnChangeMedia.isVisible = true
                binding.imageView.load(uri) {
                    crossfade(true)
                }
                processStaticImage(uri)
            }
            MODE_VIDEO -> {
                hasSelectedVideo = true
                binding.layoutPlaceholder.isVisible = false
                binding.playerView.isVisible = true
                binding.overlayView.isVisible = true
                binding.btnChangeMedia.isVisible = true
                playVideo(uri)
                initVideoRetriever(uri)
                startVideoAnalysis()
                binding.tvStatus.text = getString(R.string.status_video_playing)
            }
        }
    }

    private fun executeOnDetector(action: () -> Unit) {
        if (!detectorExecutor.isShutdown && !detectorExecutor.isTerminated) {
            try {
                detectorExecutor.execute {
                    action()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Task rejected from executor", e)
            }
        }
    }

    private fun processStaticImage(uri: Uri) {
        val currentDetector = detector
        if (currentDetector == null || !currentDetector.isLoaded) {
            binding.tvStatus.text = "Add .tflite to assets to detect"
            binding.tvDetected.text = "Detected: Model Missing"
            return
        }

        binding.tvStatus.text = "Status: Analyzing..."
        executeOnDetector {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        val width = bitmap.width
                        val height = bitmap.height
                        val result = currentDetector.detect(bitmap, binding.overlayView.minConfidence)
                        bitmap.recycle()
                        runOnUiThread {
                            if (currentMode == MODE_IMAGE && !isFinishing && !isDestroyed) {
                                binding.overlayView.sourceWidth = width
                                binding.overlayView.sourceHeight = height
                                updateDetectionUI(result.detections, result.latencyMs)
                                binding.tvStatus.text = getString(R.string.status_image_loaded)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Static image detection failed", e)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        binding.tvStatus.text = "Status: Analysis Failed"
                    }
                }
            }
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { analysis ->
                    analysis.setAnalyzer(detectorExecutor) { imageProxy ->
                        processCameraFrame(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                binding.tvStatus.text = getString(R.string.status_camera_active)
                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                binding.btnFlashToggle.isVisible = hasFlash
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                binding.tvStatus.text = "Status: Cam Error"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processCameraFrame(imageProxy: ImageProxy) {
        val currentDetector = detector
        if (currentDetector == null || !currentDetector.isLoaded || !isCameraActive || currentMode != MODE_CAMERA) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val result = currentDetector.detect(rotatedBitmap, binding.overlayView.minConfidence)

            if (rotatedBitmap !== bitmap) {
                rotatedBitmap.recycle()
            }
            bitmap.recycle()

            runOnUiThread {
                if (isCameraActive && currentMode == MODE_CAMERA && !isFinishing && !isDestroyed) {
                    binding.overlayView.sourceWidth = 0
                    binding.overlayView.sourceHeight = 0
                    updateDetectionUI(result.detections, result.latencyMs)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera frame detection error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun updateDetectionUI(detections: List<Detection>, latencyMs: Long) {
        binding.overlayView.detections = detections
        binding.tvLatency.text = "Latency: ${latencyMs}ms"

        if (detections.isEmpty()) {
            binding.tvDetected.text = "Detected: None (Road Clear)"
            binding.layoutHazardAlert.isVisible = false
        } else {
            val summary = detections.groupBy { it.label }
                .entries.joinToString(", ") { "${it.key} (${it.value.size})" }
            binding.tvDetected.text = "Detected: $summary"
            binding.layoutHazardAlert.isVisible = true
            binding.tvAlertBannerText.text = "HAZARD DETECTED: ${detections.first().label.uppercase()}"
        }
    }

    private fun playVideo(uri: Uri) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build().also { player ->
                binding.playerView.player = player
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            startVideoAnalysis()
                        } else {
                            stopVideoAnalysis()
                        }
                    }
                })
            }
        }

        exoPlayer?.let { player ->
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun initVideoRetriever(uri: Uri) {
        executeOnDetector {
            try {
                videoRetriever?.release()
                videoRetriever = MediaMetadataRetriever().apply {
                    setDataSource(this@MainActivity, uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaMetadataRetriever", e)
            }
        }
    }

    private fun startVideoAnalysis() {
        videoHandler.removeCallbacks(videoAnalysisRunnable)
        videoHandler.post(videoAnalysisRunnable)
    }

    private fun stopVideoAnalysis() {
        videoHandler.removeCallbacks(videoAnalysisRunnable)
    }

    private fun pausePlayer() {
        exoPlayer?.pause()
        stopVideoAnalysis()
    }

    private fun releasePlayer() {
        stopVideoAnalysis()
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
        binding.playerView.player = null

        executeOnDetector {
            videoRetriever?.release()
            videoRetriever = null
        }
    }

    override fun onStop() {
        super.onStop()
        stopCameraMode()
        pausePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVideoAnalysis()
        detectorExecutor.shutdown()
        detector?.close()
        detector = null
        releasePlayer()
    }
}
