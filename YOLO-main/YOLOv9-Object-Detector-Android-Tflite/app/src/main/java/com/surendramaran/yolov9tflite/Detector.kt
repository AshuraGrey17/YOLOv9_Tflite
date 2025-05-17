package com.surendramaran.yolov9tflite

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: MainActivity,  // Corrected type
    private val message: (String) -> Unit
) {
    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        val compatList = CompatibilityList()
        val options = Interpreter.Options().apply {
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                addDelegate(GpuDelegate(delegateOptions))
            } else {
                setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        labels.addAll(MetaData.extractNamesFromMetadata(model))
        if (labels.isEmpty()) {
            if (labelPath == null) {
                message("Model does not contain metadata, using default labels.")
                labels.addAll(MetaData.TEMP_CLASSES)
            } else {
                labels.addAll(MetaData.extractNamesFromLabelFile(context, labelPath))
            }
        }

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]
            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }
    }

    fun restart(isGpu: Boolean) {
        interpreter.close()
        val options = Interpreter.Options().apply {
            if (isGpu) {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    addDelegate(GpuDelegate(compatList.bestOptionsForThisDevice))
                } else {
                    setNumThreads(4)
                }
            } else {
                setNumThreads(4)
            }
        }
        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    fun close() {
        interpreter.close()
    }

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) return

        var inferenceTime = SystemClock.uptimeMillis()
        val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
        val tensorImage = TensorImage(INPUT_IMAGE_TYPE).apply { load(resizedBitmap) }
        val processedImage = imageProcessor.process(tensorImage)

        val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
        interpreter.run(processedImage.buffer, output.buffer)

        val bestBoxes = bestBox(output.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (bestBoxes.isNullOrEmpty()) {
            detectorListener.onEmptyDetect() // Fixed unresolved reference
        } else {
            detectorListener.onDetect(bestBoxes, inferenceTime) // Fixed unresolved reference
        }
    }

    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()
        val MAX_BOXES = 5  // Increase to detect more objects

        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel) {
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                val clsName = labels[maxIdx]
                val cx = array[c]
                val cy = array[c + numElements]
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w / 2F)
                val y1 = cy - (h / 2F)
                val x2 = cx + (w / 2F)
                val y2 = cy + (h / 2F)

                if (x1 in 0F..1F && y1 in 0F..1F && x2 in 0F..1F && y2 in 0F..1F) {
                    boundingBoxes.add(
                        BoundingBox(x1, y1, x2, y2, cx, cy, w, h, maxConf, maxIdx, clsName)
                    )
                }
            }
        }

        return boundingBoxes.takeIf { it.isNotEmpty() }?.let { applyNMS(it).take(MAX_BOXES) }
    }

    private fun applyNMS(boxes: List<BoundingBox>): List<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.removeFirst()
            selectedBoxes.add(first)
            sortedBoxes.removeAll { calculateIoU(first, it) >= IOU_THRESHOLD }
        }
        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersection = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val union = box1.w * box1.h + box2.w * box2.h - intersection
        return intersection / union
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.3F
        private const val IOU_THRESHOLD = 0.5F
    }
}