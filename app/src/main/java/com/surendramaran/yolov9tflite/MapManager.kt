package com.surendramaran.yolov9tflite

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*

object MapManager {
    fun setupMap(
        context: Context,
        mapView: MapView,
        lat: Double,
        lon: Double,
        records: List<DetectionRecord>
    ) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osm_prefs", Context.MODE_PRIVATE)
        )

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        val mapController = mapView.controller
        val geoPoint = GeoPoint(lat, lon)
        mapController.setZoom(15.0)
        mapController.setCenter(geoPoint)

        mapView.overlays.clear()

        // Add current location marker
        val centerMarker = Marker(mapView)
        centerMarker.position = geoPoint
        centerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        centerMarker.title = "Searched Location"
        mapView.overlays.add(centerMarker)

        // Add user location overlay
        val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        mapView.overlays.add(locationOverlay)

        // Add markers from detection records
        for (record in records) {
            val marker = Marker(mapView)
            marker.position = GeoPoint(record.latitude, record.longitude)
            marker.title = record.anomalyType
            marker.snippet = "Detected at: ${
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.timestamp))
            }"
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            val iconRes = if (record.isReported) R.drawable.marker_green else R.drawable.marker_red
            marker.icon = ContextCompat.getDrawable(context, iconRes)

            marker.setOnMarkerClickListener { _, _ ->
                if (context is MainActivity) {
                    context.showReportMenuDialog()
                }
                true
            }

            mapView.overlays.add(marker)
        }

        mapView.invalidate()
        Log.d("MapManager", "✅ Map centered on: $lat, $lon with ${records.size} records")
    }
}
