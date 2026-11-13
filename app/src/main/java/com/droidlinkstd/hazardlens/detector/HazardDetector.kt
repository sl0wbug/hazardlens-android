package com.droidlinkstd.hazardlens.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.droidlinkstd.hazardlens.data.Detection
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-performance on-device detector for road hazards using TensorFlow Lite.
 *
 * Supports Ultralytics YOLOv8 / YOLOv11 / YOLOv5 models exported to TFLite (FP32/FP16/INT8).
 * Automatically detects model input tensor dimensions, output tensor layout, and applies
 * letterbox normalization and Non-Maximum Suppression (NMS).
 */
class HazardDetector(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "HazardDetector"
        private const val DEFAULT_IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 25

        private val PREFERRED_MODEL_NAMES = listOf(
            "hazardlens_yolov8n_544.tflite",
            "hazard_model.tflite",
            "best_float32.tflite",
            "best_float16.tflite",
            "best.tflite",
            "model.tflite"
        )

        private val DEFAULT_LABELS = listOf(
            "pothole",
            "manhole",
            "crack"
        )
    }

    private var interpreter: Interpreter? = null
    var labels: List<String> = emptyList()
        private set

    var modelFileName: String? = null
        private set

    val isLoaded: Boolean
        get() = interpreter != null

    private var inputWidth: Int = 544
    private var inputHeight: Int = 544
    private var isModelQuantized: Boolean = false
    private var isNCHW: Boolean = false

    // Preallocated buffers for zero-copy high performance inference
    private var preallocatedInputBuffer: ByteBuffer? = null
    private var preallocatedOutputBuffer: ByteBuffer? = null
    private var preallocatedPixels: IntArray? = null

    init {
        loadLabels()
        loadModel()
    }

    private fun loadLabels() {
        val loaded = mutableListOf<String>()
        try {
            context.assets.open("labels.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            loaded.add(trimmed)
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No labels.txt found in assets; falling back to default classes: ${e.message}")
        }

        labels = if (loaded.isNotEmpty()) loaded else DEFAULT_LABELS
        Log.i(TAG, "Loaded ${labels.size} hazard classes: $labels")
    }

    private fun loadModel() {
        val resolvedName = findModelFile()
        if (resolvedName == null) {
            Log.w(TAG, "No .tflite model file found in assets directory.")
            return
        }

        try {
            val fileDescriptor = context.assets.openFd(resolvedName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }

            val interp = Interpreter(modelBuffer, options)
            interpreter = interp
            modelFileName = resolvedName

            // Inspect input tensor
            val inputTensor = interp.getInputTensor(0)
            val shape = inputTensor.shape() // Expected: [1, H, W, 3] or [1, 3, H, W]
            isModelQuantized = (inputTensor.dataType() == DataType.UINT8)

            if (shape.size == 4) {
                if (shape[1] == 3) {
                    isNCHW = true
                    inputHeight = shape[2]
                    inputWidth = shape[3]
                } else {
                    isNCHW = false
                    inputHeight = shape[1]
                    inputWidth = shape[2]
                }
            }

            val bytesPerChannel = if (isModelQuantized) 1 else 4
            preallocatedInputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerChannel)
                .order(ByteOrder.nativeOrder())
            preallocatedPixels = IntArray(inputWidth * inputHeight)

            val outputTensor = interp.getOutputTensor(0)
            val outShape = outputTensor.shape()
            if (outShape.size == 3) {
                val totalElements = outShape[0] * outShape[1] * outShape[2]
                preallocatedOutputBuffer = ByteBuffer.allocateDirect(totalElements * 4)
                    .order(ByteOrder.nativeOrder())
            }

            Log.i(TAG, "TFLite model successfully loaded: $resolvedName (${inputWidth}x${inputHeight}, Quantized: $isModelQuantized)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite interpreter from $resolvedName", e)
            interpreter = null
            modelFileName = null
        }
    }

    private fun findModelFile(): String? {
        val assetList = try {
            context.assets.list("") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }

        for (preferred in PREFERRED_MODEL_NAMES) {
            if (assetList.contains(preferred)) {
                return preferred
            }
        }

        return assetList.firstOrNull { it.endsWith(".tflite", ignoreCase = true) }
    }

    /**
     * Executes inference on a given [Bitmap] and returns detected road hazards.
     *
     * @param bitmap Input bitmap from camera, image gallery, or video frame.
     * @param minConfidence Minimum confidence threshold (0.0 to 1.0) to retain detections.
     */
    fun detect(bitmap: Bitmap, minConfidence: Float = 0.25f): DetectionResult {
        val interp = interpreter ?: return DetectionResult(emptyList(), 0L)

        val startTime = SystemClock.uptimeMillis()
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        if (origW <= 0 || origH <= 0) {
            return DetectionResult(emptyList(), 0L)
        }

        // 1. Preprocess: Letterbox to model dimensions
        val scale = min(inputWidth / origW, inputHeight / origH)
        val scaledW = (origW * scale).roundToInt()
        val scaledH = (origH * scale).roundToInt()
        val padX = (inputWidth - scaledW) / 2f
        val padY = (inputHeight - scaledH) / 2f

        val letterboxedBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxedBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114)) // Standard YOLO letterbox padding

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        canvas.drawBitmap(scaledBitmap, padX, padY, null)
        scaledBitmap.recycle()

        // 2. Prepare Direct ByteBuffer input (with position rewind)
        val bytesPerChannel = if (isModelQuantized) 1 else 4
        val inputBuffer = preallocatedInputBuffer ?: ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        inputBuffer.clear()

        val pixels = preallocatedPixels ?: IntArray(inputWidth * inputHeight)
        letterboxedBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        letterboxedBitmap.recycle()

        if (isNCHW) {
            // Planar format: RRR... GGG... BBB... (Ultralytics NCHW input)
            if (isModelQuantized) {
                for (pixel in pixels) {
                    inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
                }
                for (pixel in pixels) {
                    inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
                }
                for (pixel in pixels) {
                    inputBuffer.put((pixel and 0xFF).toByte())
                }
            } else {
                for (pixel in pixels) {
                    inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                }
                for (pixel in pixels) {
                    inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                }
                for (pixel in pixels) {
                    inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
                }
            }
        } else {
            // Interleaved format: RGB, RGB, RGB... (Standard NHWC)
            if (isModelQuantized) {
                for (pixel in pixels) {
                    inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
                    inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
                    inputBuffer.put((pixel and 0xFF).toByte())
                }
            } else {
                for (pixel in pixels) {
                    inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                    inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                    inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
                }
            }
        }
        inputBuffer.rewind() // CRITICAL: Reset position to 0 so C++ JNI reads the actual pixels

        // 3. Inspect output tensor shape & run inference
        val outputTensor = interp.getOutputTensor(0)
        val outShape = outputTensor.shape()

        val candidates = mutableListOf<DetectionCandidate>()

        if (interp.outputTensorCount == 1 && outShape.size == 3) {
            val dim1 = outShape[1]
            val dim2 = outShape[2]

            val isChannelFirst = (dim1 < dim2)
            val numElements = if (isChannelFirst) dim1 else dim2
            val numBoxes = if (isChannelFirst) dim2 else dim1
            val numClasses = numElements - 4

            val totalFloats = dim1 * dim2
            val outputBuffer = preallocatedOutputBuffer ?: ByteBuffer.allocateDirect(totalFloats * 4)
                .order(ByteOrder.nativeOrder())
            outputBuffer.clear()

            interp.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val floatBuffer = outputBuffer.asFloatBuffer()

            var globalMaxScore = 0f
            var bestClass = -1

            for (i in 0 until numBoxes) {
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float

                if (isChannelFirst) {
                    cx = floatBuffer.get(0 * numBoxes + i)
                    cy = floatBuffer.get(1 * numBoxes + i)
                    w = floatBuffer.get(2 * numBoxes + i)
                    h = floatBuffer.get(3 * numBoxes + i)
                } else {
                    cx = floatBuffer.get(i * numElements + 0)
                    cy = floatBuffer.get(i * numElements + 1)
                    w = floatBuffer.get(i * numElements + 2)
                    h = floatBuffer.get(i * numElements + 3)
                }

                var maxScore = 0f
                var maxClass = 0
                for (c in 0 until numClasses) {
                    val score = if (isChannelFirst) {
                        floatBuffer.get((4 + c) * numBoxes + i)
                    } else {
                        floatBuffer.get(i * numElements + 4 + c)
                    }
                    if (score > maxScore) {
                        maxScore = score
                        maxClass = c
                    }
                }

                if (maxScore > globalMaxScore) {
                    globalMaxScore = maxScore
                    bestClass = maxClass
                }

                if (maxScore >= minConfidence) {
                    // Normalize coordinates (check if already normalized or in pixel coordinates)
                    val isAlreadyNormalized = (cx <= 1.0f && cy <= 1.0f && w <= 1.0f && h <= 1.0f)
                    val pixelCx = if (isAlreadyNormalized) cx * inputWidth else cx
                    val pixelCy = if (isAlreadyNormalized) cy * inputHeight else cy
                    val pixelW = if (isAlreadyNormalized) w * inputWidth else w
                    val pixelH = if (isAlreadyNormalized) h * inputHeight else h

                    val origCx = (pixelCx - padX) / (scale * origW)
                    val origCy = (pixelCy - padY) / (scale * origH)
                    val origBoxW = pixelW / (scale * origW)
                    val origBoxH = pixelH / (scale * origH)

                    val left = max(0.0f, min(1.0f, origCx - (origBoxW / 2f)))
                    val top = max(0.0f, min(1.0f, origCy - (origBoxH / 2f)))
                    val right = max(0.0f, min(1.0f, origCx + (origBoxW / 2f)))
                    val bottom = max(0.0f, min(1.0f, origCy + (origBoxH / 2f)))

                    if (right > left && bottom > top) {
                        candidates.add(
                            DetectionCandidate(
                                box = RectF(left, top, right, bottom),
                                classIndex = maxClass,
                                confidence = maxScore
                            )
                        )
                    }
                }
            }
            Log.i(TAG, "Inference completed. Max detected score: $globalMaxScore (${labels.getOrNull(bestClass)}), Candidates: ${candidates.size}, minConfidence: $minConfidence")
        } else {
            // Alternative multi-tensor output (models exported with TFLite NMS)
            try {
                val boxes = Array(1) { Array(100) { FloatArray(4) } }
                val classes = Array(1) { FloatArray(100) }
                val scores = Array(1) { FloatArray(100) }
                val count = FloatArray(1)

                val outputs = mutableMapOf<Int, Any>(
                    0 to boxes,
                    1 to classes,
                    2 to scores,
                    3 to count
                )
                interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

                val totalDetections = min(count[0].toInt(), 100)
                for (i in 0 until totalDetections) {
                    val score = scores[0][i]
                    if (score >= minConfidence) {
                        val classIdx = classes[0][i].toInt()
                        val b = boxes[0][i]
                        val left = max(0f, min(1f, b[1]))
                        val top = max(0f, min(1f, b[0]))
                        val right = max(0f, min(1f, b[3]))
                        val bottom = max(0f, min(1f, b[2]))

                        candidates.add(
                            DetectionCandidate(
                                box = RectF(left, top, right, bottom),
                                classIndex = classIdx,
                                confidence = score
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference error during multi-output parsing", e)
            }
        }

        // 4. Non-Maximum Suppression (NMS)
        val finalDetections = applyNms(candidates, DEFAULT_IOU_THRESHOLD)
        val latencyMs = SystemClock.uptimeMillis() - startTime

        return DetectionResult(finalDetections, latencyMs)
    }

    private fun applyNms(
        candidates: List<DetectionCandidate>,
        iouThreshold: Float
    ): List<Detection> {
        val sorted = candidates.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectionCandidate>()

        for (candidate in sorted) {
            var shouldSelect = true
            for (chosen in selected) {
                if (candidate.classIndex == chosen.classIndex) {
                    val iou = calculateIoU(candidate.box, chosen.box)
                    if (iou > iouThreshold) {
                        shouldSelect = false
                        break
                    }
                }
            }
            if (shouldSelect) {
                selected.add(candidate)
                if (selected.size >= MAX_DETECTIONS) break
            }
        }

        return selected.map { candidate ->
            val label = labels.getOrElse(candidate.classIndex) { "Hazard #${candidate.classIndex}" }
            Detection(
                boundingBox = candidate.box,
                label = label,
                confidence = candidate.confidence
            )
        }
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection

        return if (union > 0f) intersection / union else 0f
    }

    override fun close() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interpreter", e)
        } finally {
            interpreter = null
            preallocatedInputBuffer = null
            preallocatedOutputBuffer = null
            preallocatedPixels = null
        }
    }

    private data class DetectionCandidate(
        val box: RectF,
        val classIndex: Int,
        val confidence: Float
    )

    data class DetectionResult(
        val detections: List<Detection>,
        val latencyMs: Long
    )
}
