package dev.windsaloft.map

import android.content.Context
import dev.windsaloft.data.Camera
import dev.windsaloft.data.MapLayer
import dev.windsaloft.wind.LatLon
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** Esri World Imagery uses z/y/x ordering, which the stock XYTileSource doesn't support. */
private object EsriWorldImagery : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Imagery © Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(index: Long): String =
        baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index)
}

val MapLayer.tileSource: ITileSource
    get() = when (this) {
        MapLayer.STREETS -> TileSourceFactory.MAPNIK
        MapLayer.TOPO -> TileSourceFactory.OpenTopo
        MapLayer.SATELLITE -> EsriWorldImagery
    }

val MapLayer.attribution: String
    get() = when (this) {
        MapLayer.STREETS -> "© OpenStreetMap contributors"
        MapLayer.TOPO -> "© OpenStreetMap contributors, SRTM · © OpenTopoMap (CC-BY-SA)"
        MapLayer.SATELLITE -> "Imagery © Esri, Maxar, Earthstar Geographics"
    }

/** Owns the osmdroid MapView and its overlays; lives as long as the composition. */
class MapHandle(context: Context, initial: Camera?) {
    val mapView = MapView(context)
    val windOverlay = WindOverlay(context.resources.displayMetrics.density)
    val myLocation = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)

    /** Bottom edge of the search bar, and top edge of the bottom panels, in screen pixels. */
    var obscuredTopPx = 0
    var panelsTopPx = 0

    var onTap: (LatLon) -> Unit = {}

    init {
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.isTilesScaledToDpi = true
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        mapView.minZoomLevel = 2.0
        mapView.maxZoomLevel = 19.0

        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onTap(LatLon(p.latitude, p.longitude)); return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                onTap(LatLon(p.latitude, p.longitude)); return true
            }
        }))
        mapView.overlays.add(windOverlay)
        myLocation.isDrawAccuracyEnabled = true
        mapView.overlays.add(myLocation)

        val cam = initial ?: Camera(LatLon(39.5, -98.35), 4.0)
        mapView.controller.setZoom(cam.zoom)
        mapView.controller.setCenter(GeoPoint(cam.center.lat, cam.center.lon))
    }

    fun camera(): Camera {
        val c = mapView.mapCenter
        return Camera(LatLon(c.latitude, c.longitude), mapView.zoomLevelDouble)
    }

    /**
     * Pans the map so [p] sits at the bottom-centre of the free area between the search bar and the
     * bottom panels, leaving room for its arrow columns above. Skips the pan if it's already close.
     */
    fun keepAbovePanels(p: LatLon) {
        val w = mapView.width
        val h = mapView.height
        if (w == 0 || panelsTopPx <= 0) return
        val px = mapView.projection.toPixels(GeoPoint(p.lat, p.lon), null)
        val margin = (28 * mapView.resources.displayMetrics.density).toInt()
        val targetX = w / 2
        val targetY = (panelsTopPx - margin).coerceAtLeast(obscuredTopPx + margin)
        if (kotlin.math.abs(px.x - targetX) < w / 10 && kotlin.math.abs(px.y - targetY) < margin) return
        val c = mapView.projection.fromPixels(w / 2 + (px.x - targetX), h / 2 + (px.y - targetY))
        mapView.controller.animateTo(c)
    }

    fun setLayer(layer: MapLayer) {
        val src = layer.tileSource
        if (mapView.tileProvider.tileSource.name() != src.name()) mapView.setTileSource(src)
    }
}
