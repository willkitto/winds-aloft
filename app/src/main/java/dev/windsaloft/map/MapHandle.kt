package dev.windsaloft.map

import android.content.Context
import android.graphics.Rect
import dev.windsaloft.data.Camera
import dev.windsaloft.data.ChartOrientation
import dev.windsaloft.data.Connectivity
import dev.windsaloft.data.MapLayer
import dev.windsaloft.wind.LatLon
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
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

/**
 * Which way the wind chart points on screen, in degrees clockwise (0 = up). [displayRotationDeg] is
 * how far the screen has turned from the device's natural orientation (Surface.ROTATION_* in degrees).
 * Sideways charts point toward the device's top edge, so a phone turned on its side keeps the chart
 * running along the phone exactly as it was when upright.
 */
fun chartTurnDegrees(mode: ChartOrientation, viewW: Int, viewH: Int, displayRotationDeg: Int): Int {
    val deviceTop = (360 - displayRotationDeg % 360) % 360
    val sideways = if (deviceTop == 90 || deviceTop == 270) deviceTop else 90
    return when (mode) {
        ChartOrientation.VERTICAL -> 0
        ChartOrientation.HORIZONTAL -> sideways
        ChartOrientation.LONGEST -> if (viewW > viewH) sideways else 0
    }
}

/** Owns the osmdroid MapView and its overlays; lives as long as the composition. */
class MapHandle(context: Context, initial: Camera?) {
    // Tiles are only downloaded when the internet is really reachable; otherwise the map draws saved
    // tiles (and scaled-up lower-zoom ones where it has nothing closer) without waiting on the network.
    private val tiles = MapTileProviderBasic(
        SimpleRegisterReceiver(context.applicationContext), Connectivity(context.applicationContext),
        TileSourceFactory.OpenTopo, context.applicationContext, null,
    )
    val mapView = MapView(context, tiles)
    val windOverlay = WindOverlay(context.resources.displayMetrics.density)
    val myLocation = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)

    /** Screen areas covered by panels (search bar, controls...), used to place the chart in the clear part. */
    val obstacles = mutableMapOf<String, Rect>()

    /** Which way the chart points on screen (degrees clockwise), see [chartTurnDegrees]. */
    var chartTurn = 0
        set(v) { field = v; windOverlay.chartTurn = v.toFloat() }

    /** Long-press: propose a new forecast spot (the user confirms before anything is fetched). */
    var onLongPress: (LatLon) -> Unit = {}

    init {
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.isTilesScaledToDpi = true
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        mapView.minZoomLevel = 2.0
        mapView.maxZoomLevel = 19.0

        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            // A plain tap does nothing, so panning and zooming never move the forecast spot by accident.
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false

            override fun longPressHelper(p: GeoPoint): Boolean {
                onLongPress(LatLon(p.latitude, p.longitude)); return true
            }
        }))
        // Two-finger rotation; the compass button turns the map back to north-up.
        mapView.overlays.add(RotationGestureOverlay(mapView).apply { isEnabled = true })
        mapView.overlays.add(windOverlay)
        myLocation.isDrawAccuracyEnabled = true
        mapView.overlays.add(myLocation)

        val cam = initial ?: Camera(LatLon(39.5, -98.35), 4.0)
        mapView.controller.setZoom(cam.zoom)
        mapView.controller.setCenter(GeoPoint(cam.center.lat, cam.center.lon))
        mapView.mapOrientation = cam.orientation
    }

    private var displayRotation: Int? = null

    /**
     * Turning the phone turns the screen (so panels and the keyboard come up the right way), but the
     * map shouldn't move: rotate it back by the same amount, about the screen centre, so everything on
     * the map stays exactly where it was under your finger. The compass then shows north is sideways,
     * and tapping it turns the map north-up for the new way you're holding the phone.
     */
    fun onDisplayRotation(deg: Int) {
        val old = displayRotation
        displayRotation = deg
        if (old == null || old == deg) return
        mapView.mapOrientation = ((mapView.mapOrientation - (deg - old)) % 360 + 360) % 360
    }

    fun camera(): Camera {
        val c = mapView.mapCenter
        return Camera(LatLon(c.latitude, c.longitude), mapView.zoomLevelDouble, mapView.mapOrientation)
    }

    /**
     * Pans the map so [p] sits at the base of the clear area (the part not under panels), on the side
     * opposite the chart's direction, leaving the chart room to run across the clear area. Skips the
     * pan if it's already close.
     */
    fun keepAbovePanels(p: LatLon) {
        val w = mapView.width
        val h = mapView.height
        if (w == 0 || h == 0) return
        val free = clearArea(Rect(0, 0, w, h), obstacles.values)
        val m = (28 * mapView.resources.displayMetrics.density).toInt()
        val (tx, ty) = when (chartTurn) {
            90 -> free.left + m to free.centerY()
            180 -> free.centerX() to free.top + m
            270 -> free.right - m to free.centerY()
            else -> free.centerX() to free.bottom - m
        }
        val proj = mapView.projection
        // toPixels/fromPixels work in the map's un-rotated frame; the target is in screen pixels.
        val px = proj.toPixels(GeoPoint(p.lat, p.lon), null)
        val onScreen = proj.rotateAndScalePoint(px.x, px.y, null)
        if (kotlin.math.abs(onScreen.x - tx) < m && kotlin.math.abs(onScreen.y - ty) < m) return
        val t = proj.unrotateAndScalePoint(tx, ty, null)
        mapView.controller.animateTo(proj.fromPixels(w / 2 + (px.x - t.x), h / 2 + (px.y - t.y)))
    }

    /** Animates the map back to north-up. */
    fun resetNorth() {
        mapView.controller.animateTo(mapView.mapCenter, mapView.zoomLevelDouble, 400L, 0f)
    }

    fun setLayer(layer: MapLayer) {
        val src = layer.tileSource
        if (mapView.tileProvider.tileSource.name() != src.name()) mapView.setTileSource(src)
    }
}

/**
 * The largest part of [screen] left clear by [covered] panels: each panel is cut off along whichever
 * of its edges leaves the most room.
 */
fun clearArea(screen: Rect, covered: Collection<Rect>): Rect {
    var free = Rect(screen)
    for (o in covered) {
        if (o.isEmpty || !Rect.intersects(free, o)) continue
        val options = listOf(
            Rect(free.left, free.top, free.right, o.top), Rect(free.left, o.bottom, free.right, free.bottom),
            Rect(free.left, free.top, o.left, free.bottom), Rect(o.right, free.top, free.right, free.bottom),
        ).filter { it.width() > 0 && it.height() > 0 }
        free = options.maxByOrNull { it.width().toLong() * it.height() } ?: free
    }
    return free
}
