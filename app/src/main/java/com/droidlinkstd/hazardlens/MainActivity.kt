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
    private var currentMode = MODE_CAMERA
    private var hasSelectedImage = false
    private var hasSelectedVideo = false

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
            binding.tvStatus.text = "Status: No Camera Permission"
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
        setMockDetections()

        // Default to Camera mode
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

    private fun setupActionButtons() {
        binding.btnPickMedia.setOnClickListener {
            launchMediaPicker()
        }

        binding.btnPlaceholderSelect.setOnClickListener {
            launchMediaPicker()
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
                binding.previewView.isVisible = true
                binding.imageView.isVisible = false
                binding.playerView.isVisible = false
                binding.layoutPlaceholder.isVisible = false
                binding.btnPickMedia.isVisible = false
                binding.overlayView.isVisible = true

                pausePlayer()
                checkCameraPermissionAndStart()
                binding.tvStatus.text = "Status: Live Camera"
            }
            MODE_IMAGE -> {
                binding.previewView.isVisible = false
                binding.playerView.isVisible = false
                binding.btnPickMedia.isVisible = true
                binding.btnPickMedia.text = getString(R.string.action_choose_image)

                pausePlayer()
                if (hasSelectedImage) {
                    binding.imageView.isVisible = true
                    binding.layoutPlaceholder.isVisible = false
                    binding.overlayView.isVisible = true
                    binding.tvStatus.text = "Status: Image Loaded"
                } else {
                    binding.imageView.isVisible = false
                    binding.layoutPlaceholder.isVisible = true
                    binding.overlayView.isVisible = false
                    binding.ivPlaceholderIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                    binding.tvPlaceholderTitle.text = getString(R.string.placeholder_image_title)
                    binding.tvPlaceholderDesc.text = getString(R.string.placeholder_image_desc)
                    binding.btnPlaceholderSelect.text = getString(R.string.action_choose_image)
                    binding.tvStatus.text = "Status: Ready to Import Image"
                }
            }
            MODE_VIDEO -> {
                binding.previewView.isVisible = false
                binding.imageView.isVisible = false
                binding.btnPickMedia.isVisible = true
                binding.btnPickMedia.text = getString(R.string.action_choose_video)

                if (hasSelectedVideo) {
                    binding.playerView.isVisible = true
                    binding.layoutPlaceholder.isVisible = false
                    binding.overlayView.isVisible = true
                    exoPlayer?.play()
                    binding.tvStatus.text = "Status: Video Playing"
                } else {
                    binding.playerView.isVisible = false
                    binding.layoutPlaceholder.isVisible = true
                    binding.overlayView.isVisible = false
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
                binding.imageView.load(uri) {
                    crossfade(true)
                }
                binding.tvStatus.text = "Status: Image Loaded"
            }
            MODE_VIDEO -> {
                hasSelectedVideo = true
                binding.layoutPlaceholder.isVisible = false
                binding.playerView.isVisible = true
                binding.overlayView.isVisible = true
                playVideo(uri)
                binding.tvStatus.text = "Status: Video Playing"
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
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
                binding.tvStatus.text = "Status: Camera Active"
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

    /**
     * Injects mock detections into the overlay view to test canvas rendering immediately.
     */
    private fun setMockDetections() {
        val sampleDetections = listOf(
            Detection(
                boundingBox = RectF(0.15f, 0.42f, 0.55f, 0.68f),
                label = "Pothole",
                confidence = 0.88f
            ),
            Detection(
                boundingBox = RectF(0.58f, 0.58f, 0.92f, 0.84f),
                label = "Speed Breaker",
                confidence = 0.94f
            )
        )
        binding.overlayView.detections = sampleDetections
    }

    override fun onStop() {
        super.onStop()
        pausePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}