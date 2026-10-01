package com.backpack.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.backpack.app.R
import com.backpack.app.core.LatLon
import com.backpack.app.core.RouteResult
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Everything Backpack draws on the MapLibre map: route lines, the start /
 * stop / destination pins, POIs and the GPS-warning sign. The data is kept
 * here, so after a style change (day <-> night) [install] puts it all back.
 */
class MapLayers(private val context: Context) {

    data class Pin(val id: String, val point: LatLon, val icon: String)

    private var style: Style? = null
    private var routes: List<RouteResult> = emptyList()
    private var selected = 0
    private var pins: List<Pin> = emptyList()
    private var pois: List<Pin> = emptyList()
    private var warning: LatLon? = null

    /** Icon names usable in [Pin.icon], mapped to the app's own drawables. */
    private val iconRes = mapOf(
        ICON_START to R.drawable.ic_marker_start,
        ICON_DEST to R.drawable.ic_marker_destination,
        ICON_STOP to R.drawable.ic_marker_waypoint,
        ICON_WARN to R.drawable.ic_gps_warning,
        "poi_fuel" to R.drawable.ic_poi_fuel,
        "poi_food" to R.drawable.ic_poi_food,
        "poi_lodging" to R.drawable.ic_poi_lodging,
        "poi_parking" to R.drawable.ic_poi_parking,
        "poi_toilets" to R.drawable.ic_poi_toilets,
        "poi_emergency" to R.drawable.ic_poi_emergency
    )

    fun install(style: Style) {
        this.style = style
        for ((name, res) in iconRes) {
            bitmap(res)?.let { style.addImage(name, it) }
        }
        val primary = ContextCompat.getColor(context, R.color.route_primary)
        val casing = ContextCompat.getColor(context, R.color.route_casing)
        val alt = ContextCompat.getColor(context, R.color.route_alt)

        style.addSource(GeoJsonSource(SRC_ALT))
        style.addSource(GeoJsonSource(SRC_ROUTE))
        style.addSource(GeoJsonSource(SRC_POI))
        style.addSource(GeoJsonSource(SRC_PINS))
        style.addSource(GeoJsonSource(SRC_WARN))

        // Keep everything under the position arrow: after a style change
        // MapLibre has already put its location layers back before we get here.
        val firstLocationLayer = style.layers.firstOrNull { it.id.startsWith("mapbox-location-") }?.id
        fun add(layer: Layer) {
            if (firstLocationLayer != null) style.addLayerBelow(layer, firstLocationLayer) else style.addLayer(layer)
        }

        // Width grows with zoom so the line reads at city and motorway scale.
        fun width(base: Float) = Expression.interpolate(
            Expression.exponential(1.5f), Expression.zoom(),
            Expression.stop(8, base * 0.5f), Expression.stop(14, base), Expression.stop(18, base * 2.2f)
        )
        add(LineLayer(LAYER_ALT_CASING, SRC_ALT).withProperties(
            PropertyFactory.lineColor(casing), PropertyFactory.lineWidth(width(9f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.8f)
        ))
        add(LineLayer(LAYER_ALT, SRC_ALT).withProperties(
            PropertyFactory.lineColor(alt), PropertyFactory.lineWidth(width(6f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        add(LineLayer(LAYER_ROUTE_CASING, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(casing), PropertyFactory.lineWidth(width(11f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        add(LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(primary), PropertyFactory.lineWidth(width(7f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        add(SymbolLayer(LAYER_POI, SRC_POI).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        ))
        add(SymbolLayer(LAYER_PINS, SRC_PINS).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        ))
        add(SymbolLayer(LAYER_WARN, SRC_WARN).withProperties(
            PropertyFactory.iconImage(ICON_WARN),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconOpacity(0.75f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        ))
        pushAll()
    }

    fun setRoutes(routes: List<RouteResult>, selectedIndex: Int) {
        this.routes = routes
        selected = selectedIndex
        pushRoutes()
    }

    fun clearRoutes() = setRoutes(emptyList(), 0)

    fun setPins(pins: List<Pin>) {
        this.pins = pins
        pushPoints(SRC_PINS, pins)
    }

    fun setPois(pois: List<Pin>) {
        this.pois = pois
        pushPoints(SRC_POI, pois)
    }

    fun setWarning(at: LatLon?) {
        warning = at
        pushWarning()
    }

    /** Index of an alternative route line under the finger, or null. */
    fun alternativeAt(map: MapLibreMap, screen: PointF): Int? {
        val r = 14f * context.resources.displayMetrics.density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        val hit = map.queryRenderedFeatures(box, LAYER_ALT).firstOrNull() ?: return null
        return hit.getNumberProperty("idx")?.toInt()
    }

    private fun pushAll() {
        pushRoutes()
        pushPoints(SRC_PINS, pins)
        pushPoints(SRC_POI, pois)
        pushWarning()
    }

    private fun pushRoutes() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val altFeatures = routes.withIndex().filter { it.index != selected }.map { (i, r) ->
            Feature.fromGeometry(line(r)).also { it.addNumberProperty("idx", i) }
        }
        s.getSourceAs<GeoJsonSource>(SRC_ALT)?.setGeoJson(FeatureCollection.fromFeatures(altFeatures))
        val primary = routes.getOrNull(selected)
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(
            FeatureCollection.fromFeatures(if (primary == null) emptyList() else listOf(Feature.fromGeometry(line(primary))))
        )
    }

    private fun pushPoints(source: String, list: List<Pin>) {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val features = list.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.point.lon, p.point.lat)).also { it.addStringProperty("icon", p.icon) }
        }
        s.getSourceAs<GeoJsonSource>(source)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun pushWarning() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val w = warning
        s.getSourceAs<GeoJsonSource>(SRC_WARN)?.setGeoJson(
            FeatureCollection.fromFeatures(if (w == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(w.lon, w.lat))))
        )
    }

    private fun line(r: RouteResult): LineString = LineString.fromLngLats(r.points.map { Point.fromLngLat(it.lon, it.lat) })

    private fun bitmap(res: Int): Bitmap? = ContextCompat.getDrawable(context, res)?.toBitmap()

    companion object {
        const val ICON_START = "pin_start"
        const val ICON_DEST = "pin_dest"
        const val ICON_STOP = "pin_stop"
        const val ICON_WARN = "gps_warn"

        private const val SRC_ALT = "bp-alt-routes"
        private const val SRC_ROUTE = "bp-route"
        private const val SRC_POI = "bp-poi"
        private const val SRC_PINS = "bp-pins"
        private const val SRC_WARN = "bp-warn"
        private const val LAYER_ALT_CASING = "bp-alt-casing"
        const val LAYER_ALT = "bp-alt"
        private const val LAYER_ROUTE_CASING = "bp-route-casing"
        private const val LAYER_ROUTE = "bp-route-line"
        private const val LAYER_POI = "bp-poi-layer"
        private const val LAYER_PINS = "bp-pins-layer"
        private const val LAYER_WARN = "bp-warn-layer"
    }
}
