package com.droidlinkstd.hazardlens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.droidlinkstd.hazardlens.data.Detection
import com.droidlinkstd.hazardlens.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

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

        setupTabNavigation()
        setupActionButtons()
        setupSensitivityControls()

        // Clear mock detections so canvas only displays real detections after training
        binding.overlayView.detections = emptyList()
        binding.tvDetected.text = "Detected: None"
        binding.tvLatency.text = "Latency: --"

        // Default to Camera mode (shows "Open Camera" placeholder)
        updateDetectionMode(MODE_CAMERA)
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
        binding.chipGroupConfidence.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipConfidence30) -> {
                    binding.overlayView.minConfidence = 0.30f
                }
                checkedIds.contains(R.id.chipConfidence50) -> {
                    binding.overlayView.minConfidence = 0.50f
                }
                checkedIds.contains(R.id.chipConfidence70) -> {
                    binding.overlayView.minConfidence = 0.70f
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
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_specs_title))
            .setMessage(
                """
                • System Architecture:
                   On-device Mobile Edge Computer Vision (Zero Cloud Dependency for Highway Reliability)
                
                • Targeted Domain & Classes (BD Road Dataset):
                   1. Potholes (Waterlogged, Dry & Deep Pits)
                   2. Unmarked Speed Breakers & Asphalt Humps
                   3. Open Drainage / Broken Manholes
                   4. Severe Longitudinal Road Cracks
                
                • Vision Execution Pipeline:
                   - Ingestion: CameraX ImageAnalysis (YUV_420_888 / RGBA)
                   - Preprocessing: Letterbox 640x640 Normalization
                   - Core Model: Lightweight YOLO (YOLOv8n / YOLOv11n) converted to TFLite (FP16/INT8)
                   - Accelerator: Android NNAPI / GPU Delegate
                   - Latency SLA: 35-50ms (< 25MB footprint)
                   - Output: Real-time Bounding Box HUD & Proximity Warning
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

                if (hasSelectedImage) {
                    binding.imageView.isVisible = true
                    binding.layoutPlaceholder.isVisible = false
                    binding.overlayView.isVisible = true
                    binding.btnChangeMedia.isVisible = true
                    binding.tvStatus.text = getString(R.string.status_image_loaded)
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
                binding.layoutPlaceholder.isVisible = false
                binding.imageView.isVisible = true
                binding.overlayView.isVisible = true
                binding.btnChangeMedia.isVisible = true
                binding.imageView.load(uri) {
                    crossfade(true)
                }
                binding.tvStatus.text = getString(R.string.status_image_loaded)
            }
            MODE_VIDEO -> {
                hasSelectedVideo = true
                binding.layoutPlaceholder.isVisible = false
                binding.playerView.isVisible = true
                binding.overlayView.isVisible = true
                binding.btnChangeMedia.isVisible = true
                playVideo(uri)
                binding.tvStatus.text = getString(R.string.status_video_playing)
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
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview)
                binding.tvStatus.text = getString(R.string.status_camera_active)
                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                binding.btnFlashToggle.isVisible = hasFlash
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                binding.tvStatus.text = "Status: Cam Error"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun playVideo(uri: Uri) {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build().also { player ->
                binding.playerView.player = player
            }
        }

        exoPlayer?.let { player ->
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun pausePlayer() {
        exoPlayer?.pause()
    }

    private fun releasePlayer() {
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
        binding.playerView.player = null
    }

    override fun onStop() {
        super.onStop()
        stopCameraMode()
        pausePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}