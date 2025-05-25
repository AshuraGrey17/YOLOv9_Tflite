package com.surendramaran.yolov9tflite

data class DetectionRecord(
    val anomalyType: String,
    val imagePath: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    var isReported: Boolean = false
)
