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
    }

    private fun updateDetectionMode(mode: Int) {
        currentMode = mode

        when (mode) {
            MODE_CAMERA -> {
                binding.previewView.isVisible = true
                binding.imageView.isVisible = false
                binding.playerView.isVisible = false
                binding.btnPickMedia.isVisible = false

                pausePlayer()
                checkCameraPermissionAndStart()
                binding.tvStatus.text = "Status: Live Camera"
            }
            MODE_IMAGE -> {
                binding.previewView.isVisible = false
                binding.imageView.isVisible = true
                binding.playerView.isVisible = false
                binding.btnPickMedia.isVisible = true
                binding.btnPickMedia.text = "Pick Road Image"

                pausePlayer()
                binding.tvStatus.text = "Status: Static Image"
            }
            MODE_VIDEO -> {
                binding.previewView.isVisible = false
                binding.imageView.isVisible = false
                binding.playerView.isVisible = true
                binding.btnPickMedia.isVisible = true
                binding.btnPickMedia.text = "Pick Road Video"

                binding.tvStatus.text = "Status: Road Video"
            }
        }
    }

    private fun handleSelectedMedia(uri: Uri) {
        when (currentMode) {
            MODE_IMAGE -> {
                binding.imageView.load(uri) {
                    crossfade(true)
                }
                binding.tvStatus.text = "Status: Image Loaded"
            }
            MODE_VIDEO -> {
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