package com.backpack.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.backpack.app.R
import com.backpack.app.core.LatLon
import com.backpack.app.core.RouteLabels
import com.backpack.app.core.RouteResult
import com.backpack.app.core.TouristCategory
import com.backpack.app.core.TouristPoi
import com.backpack.app.ui.LetterBadge
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
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
    /** A / B / C letters on the lines (only while choosing between routes). */
    private var letters = false
    private var pins: List<Pin> = emptyList()
    private var pois: List<Pin> = emptyList()
    private var warning: LatLon? = null
    private var tourist: List<TouristPoi> = emptyList()

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
        for (i in 0 until 4) {
            val l = RouteLabels.letter(i)
            style.addImage("badge_$l", LetterBadge.bitmap(context, l, false))
            style.addImage("badge_${l}_sel", LetterBadge.bitmap(context, l, true))
        }
        val primary = ContextCompat.getColor(context, R.color.route_primary)
        val casing = ContextCompat.getColor(context, R.color.route_casing)
        val alt = ContextCompat.getColor(context, R.color.route_alt)

        style.addSource(GeoJsonSource(SRC_ALT))
        style.addSource(GeoJsonSource(SRC_ROUTE))
        style.addSource(GeoJsonSource(SRC_POI))
        style.addSource(GeoJsonSource(SRC_PINS))
        style.addSource(GeoJsonSource(SRC_WARN))
        style.addSource(GeoJsonSource(SRC_TOUR))
        style.addSource(GeoJsonSource(SRC_LABELS))

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
        add(SymbolLayer(LAYER_LABELS, SRC_LABELS).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        ))
        // Tourist places: coloured dot by kind, bigger when better known,
        // name under the well-known ones (and all of them when zoomed in).
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        add(CircleLayer(LAYER_TOUR, SRC_TOUR).withProperties(
            PropertyFactory.circleColor(Expression.match(
                Expression.get("cat"), Expression.color(CAT_COLORS.getValue(TouristCategory.SIGHT)),
                *TouristCategory.values().map { Expression.stop(it.name, Expression.color(CAT_COLORS.getValue(it))) }.toTypedArray()
            )),
            PropertyFactory.circleRadius(Expression.interpolate(
                Expression.linear(), Expression.toNumber(Expression.get("stars")),
                Expression.stop(0, 6f), Expression.stop(3, 11f)
            )),
            PropertyFactory.circleStrokeColor(if (night) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()),
            PropertyFactory.circleStrokeWidth(2f)
        ))
        add(SymbolLayer(LAYER_TOUR_LABEL, SRC_TOUR).withProperties(
            PropertyFactory.textField(Expression.step(
                Expression.zoom(),
                Expression.switchCase(
                    Expression.gte(Expression.toNumber(Expression.get("stars")), Expression.literal(1)),
                    Expression.get("name"), Expression.literal("")
                ),
                Expression.stop(13, Expression.get("name"))
            )),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textMaxWidth(8f),
            PropertyFactory.textOptional(true),
            PropertyFactory.textColor(if (night) 0xFFEEEEEE.toInt() else 0xFF1A1A1A.toInt()),
            PropertyFactory.textHaloColor(if (night) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()),
            PropertyFactory.textHaloWidth(1.5f)
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

    fun setRoutes(routes: List<RouteResult>, selectedIndex: Int, letters: Boolean = routes.size > 1) {
        this.routes = routes
        selected = selectedIndex
        this.letters = letters && routes.size > 1
        pushRoutes()
    }

    fun clearRoutes() = setRoutes(emptyList(), 0)

    /** Id of the everyday place (POI button) under the finger, or null. */
    fun poiAt(map: MapLibreMap, screen: PointF): String? {
        val r = 18f * context.resources.displayMetrics.density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, LAYER_POI).firstOrNull()?.getStringProperty("id")
    }

    fun setPins(pins: List<Pin>) {
        this.pins = pins
        pushPoints(SRC_PINS, pins)
    }

    fun setPois(pois: List<Pin>) {
        this.pois = pois
        pushPoints(SRC_POI, pois)
    }

    fun setTourist(list: List<TouristPoi>) {
        tourist = list
        pushTourist()
    }

    /** Key of the tourist place under the finger, or null. */
    fun touristAt(map: MapLibreMap, screen: PointF): String? {
        val r = 16f * context.resources.displayMetrics.density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, LAYER_TOUR).firstOrNull()?.getStringProperty("key")
    }

    private fun pushTourist() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val features = tourist.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.point.lon, p.point.lat)).also {
                it.addStringProperty("key", p.key)
                it.addStringProperty("name", p.name)
                it.addStringProperty("cat", p.category.name)
                it.addNumberProperty("stars", p.stars)
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_TOUR)?.setGeoJson(FeatureCollection.fromFeatures(features))
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
        pushTourist()
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
        val labels = if (!letters) emptyList() else {
            RouteLabels.points(routes.map { it.line }).mapIndexedNotNull { i, p ->
                if (p == null || i > 3) null else Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                    val l = RouteLabels.letter(i)
                    it.addStringProperty("icon", if (i == selected) "badge_${l}_sel" else "badge_$l")
                    it.addNumberProperty("idx", i)
                }
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_LABELS)?.setGeoJson(FeatureCollection.fromFeatures(labels))
    }

    /** Index of the route whose letter is under the finger, or null. */
    fun letterAt(map: MapLibreMap, screen: PointF): Int? {
        val r = 14f * context.resources.displayMetrics.density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, LAYER_LABELS).firstOrNull()?.getNumberProperty("idx")?.toInt()
    }

    private fun pushPoints(source: String, list: List<Pin>) {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val features = list.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.point.lon, p.point.lat)).also {
                it.addStringProperty("icon", p.icon)
                it.addStringProperty("id", p.id)
            }
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
        private const val SRC_TOUR = "bp-tourist"
        private const val SRC_LABELS = "bp-route-letters"
        private const val LAYER_LABELS = "bp-route-letters-layer"
        private const val LAYER_TOUR = "bp-tourist-dots"
        private const val LAYER_TOUR_LABEL = "bp-tourist-labels"

        val CAT_COLORS = mapOf(
            TouristCategory.SIGHT to 0xFF8A0000.toInt(),
            TouristCategory.MUSEUM to 0xFF6A1B9A.toInt(),
            TouristCategory.HISTORIC to 0xFF8D5524.toInt(),
            TouristCategory.VIEWPOINT to 0xFF00796B.toInt(),
            TouristCategory.NATURE to 0xFF2E7D32.toInt()
        )
        private const val LAYER_ALT_CASING = "bp-alt-casing"
        const val LAYER_ALT = "bp-alt"
        private const val LAYER_ROUTE_CASING = "bp-route-casing"
        private const val LAYER_ROUTE = "bp-route-line"
        private const val LAYER_POI = "bp-poi-layer"
        private const val LAYER_PINS = "bp-pins-layer"
        private const val LAYER_WARN = "bp-warn-layer"
    }
}
