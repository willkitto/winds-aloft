package dev.windsaloft.map

import android.content.Context
import dev.windsaloft.data.Camera
import dev.windsaloft.data.MapLayer
import dev.windsaloft.wind.Bbox
import dev.windsaloft.wind.LatLon
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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

    /** Screen pixels covered by UI at the top / bottom, excluded from the wind grid. */
    var obscuredTopPx = 0
    var obscuredBottomPx = 0

    /** Top edge of everything at the bottom of the screen (pin panel + controls). */
    var panelsTopPx = 0

    var onTap: (LatLon) -> Unit = {}
    var onViewport: (Bbox) -> Unit = {}

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

        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { reportViewport(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { reportViewport(); return false }
        })
        mapView.addOnFirstLayoutListener { _, _, _, _, _ -> reportViewport() }
    }

    fun camera(): Camera {
        val c = mapView.mapCenter
        return Camera(LatLon(c.latitude, c.longitude), mapView.zoomLevelDouble)
    }

    fun reportViewport() {
        val w = mapView.width
        val h = mapView.height
        if (w == 0 || h == 0) return
        val top = obscuredTopPx.coerceIn(0, h / 2)
        val bottom = (h - obscuredBottomPx).coerceIn(top + 1, h)
        val proj = mapView.projection
        val nw = proj.fromPixels(0, top)
        val se = proj.fromPixels(w, bottom)
        onViewport(Bbox(nw.latitude, se.latitude, se.longitude, nw.longitude))
    }

    /** Pans the map, if needed, so [p] sits just above the bottom panels with room for its arrow column above. */
    fun keepAbovePanels(p: LatLon) {
        val h = mapView.height
        if (h == 0 || panelsTopPx <= 0) return
        val px = mapView.projection.toPixels(GeoPoint(p.lat, p.lon), null)
        val visibleTop = obscuredTopPx
        val target = (visibleTop + (panelsTopPx - visibleTop) * 0.85f).toInt()
        if (px.y in visibleTop..target) return
        val dy = px.y - target
        val c = mapView.projection.fromPixels(mapView.width / 2, h / 2 + dy)
        mapView.controller.animateTo(c)
    }

    fun setLayer(layer: MapLayer) {
        val src = layer.tileSource
        if (mapView.tileProvider.tileSource.name() != src.name()) mapView.setTileSource(src)
    }
}
