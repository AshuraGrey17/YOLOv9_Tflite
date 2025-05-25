package com.surendramaran.yolov9tflite

import android.content.Context
import android.location.Geocoder
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
    private var isShowingSearchResult = false
    private var userLocation: GeoPoint? = null

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

        val geoPoint = GeoPoint(lat, lon)
        if (!isShowingSearchResult) {
            userLocation = geoPoint
        }

        val mapController = mapView.controller
        mapController.setZoom(15.0)
        mapController.setCenter(geoPoint)

        // ✅ Instead of clearing all overlays, remove only the markers you control
        mapView.overlays.removeAll {
            it is Marker && it.title != "Your Location" && it.title != "Searched Location"
        }

        // ✅ Re-add your location or search marker
        val centerMarker = Marker(mapView)
        centerMarker.position = geoPoint
        centerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        centerMarker.title = if (isShowingSearchResult) "Searched Location" else "Your Location"
        mapView.overlays.add(centerMarker)

        // ✅ Re-add user GPS overlay
        val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        mapView.overlays.add(locationOverlay)

        // ✅ Add red and green markers from detectionRecords
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
                    context.selectedDetectionRecord = record
                    context.showReportMenuDialog()
                }
                true
            }

            mapView.overlays.add(marker)
        }

        mapView.invalidate()
        Log.d("MapManager", "✅ Map centered on: $lat, $lon with ${records.size} records")
    }



    fun addReportedHazardMarker(
        context: Context,
        mapView: MapView,
        location: GeoPoint,
        type: String
    ) {
        val marker = Marker(mapView)
        marker.position = location
        marker.title = type
        marker.icon = ContextCompat.getDrawable(context, R.drawable.marker_green)
        mapView.overlays.add(marker)
        mapView.invalidate()
    }




    fun searchLocation(context: Context, mapView: MapView, query: String, records: List<DetectionRecord>) {
        try {
            val geocoder = Geocoder(context)
            val results = geocoder.getFromLocationName(query, 1)
            if (!results.isNullOrEmpty()) {
                val location = results[0]
                isShowingSearchResult = true
                setupMap(context, mapView, location.latitude, location.longitude, records)
            } else {
                Log.w("MapManager", "❌ No results found for '$query'")
            }
        } catch (e: Exception) {
            Log.e("MapManager", "❌ Error during geocoding: ${e.message}", e)
        }
    }

    fun resetToUserLocation(context: Context, mapView: MapView, records: List<DetectionRecord>) {
        userLocation?.let {
            isShowingSearchResult = false
            setupMap(context, mapView, it.latitude, it.longitude, records)
        }
    }

    fun isSearchActive(): Boolean = isShowingSearchResult

    fun clearSearchState() {
        isShowingSearchResult = false
    }
}
