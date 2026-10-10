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

    /** [dim]: 1.3.0 a place closed right now (drawn faint). */
    data class Pin(val id: String, val point: LatLon, val icon: String, val dim: Boolean = false)

    private var style: Style? = null
    /** Font of the place names: from the map style's own font set. */
    var labelFont = "Noto Sans Regular"
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
        ICON_TURN to R.drawable.ic_turn_head,
        "poi_fuel" to R.drawable.ic_poi_fuel,
        "poi_food" to R.drawable.ic_poi_food,
        "poi_lodging" to R.drawable.ic_poi_lodging,
        "poi_parking" to R.drawable.ic_poi_parking,
        "poi_toilets" to R.drawable.ic_poi_toilets,
        "poi_emergency" to R.drawable.ic_poi_emergency,
        "poi_water" to R.drawable.ic_poi_water,
        "poi_sleep" to R.drawable.ic_poi_sleep,
        "poi_laundry" to R.drawable.ic_poi_laundry,
        "poi_pharmacy" to R.drawable.ic_poi_pharmacy,
        "poi_money" to R.drawable.ic_poi_money,
        "poi_wifi" to R.drawable.ic_poi_wifi,
        "poi_bike" to R.drawable.ic_poi_bike
    )

    fun install(style: Style) {
        this.style = style
        for ((name, res) in iconRes) {
            bitmap(res)?.let { style.addImage(name, it) }
        }
        // 1.5.1: the turn arrowhead in the map's colours (white + red border on dark maps).
        val darkNow = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (darkNow) bitmap(R.drawable.ic_turn_head_dark)?.let { style.addImage(ICON_TURN, it) }
        for (i in 0 until 4) {
            val l = RouteLabels.letter(i)
            style.addImage("badge_$l", LetterBadge.bitmap(context, l, false))
            style.addImage("badge_${l}_sel", LetterBadge.bitmap(context, l, true))
        }
        val primary = ContextCompat.getColor(context, R.color.route_primary)
        val casing = ContextCompat.getColor(context, R.color.route_casing)
        val alt = ContextCompat.getColor(context, R.color.route_alt)

        style.addSource(GeoJsonSource(SRC_ALT))
        // 1.4.0: line metrics, so the part behind the arrow fades exactly at it (gradient).
        style.addSource(GeoJsonSource(SRC_ROUTE, org.maplibre.android.style.sources.GeoJsonOptions().withLineMetrics(true)))
        style.addSource(GeoJsonSource(SRC_TRACK))
        style.addSource(GeoJsonSource(SRC_NEXT))
        style.addSource(GeoJsonSource(SRC_FOCUS))
        style.addSource(GeoJsonSource(SRC_DONE))
        style.addSource(GeoJsonSource(SRC_POI))
        style.addSource(GeoJsonSource(SRC_PINS))
        style.addSource(GeoJsonSource(SRC_WARN))
        style.addSource(GeoJsonSource(SRC_TOUR))
        style.addSource(GeoJsonSource(SRC_LABELS))

        // Keep everything under the position arrow: after a style change
        // MapLibre has already put its location layers back before we get here.
        // 1.4.0: any naming of MapLibre's location layers ("mapbox-location-..." / "maplibre-location-...").
        val firstLocationLayer = style.layers.firstOrNull { it.id.contains("-location-") }?.id
        fun add(layer: Layer) {
            if (firstLocationLayer != null) style.addLayerBelow(layer, firstLocationLayer) else style.addLayer(layer)
        }

        // Width grows with zoom so the line reads at city and motorway scale.
        fun width(base: Float) = Expression.interpolate(
            Expression.exponential(1.5f), Expression.zoom(),
            Expression.stop(8, base * 0.5f), Expression.stop(14, base), Expression.stop(18, base * 2.2f)
        )
        routeColor = primary
        casingColor = casing
        doneColor = (ContextCompat.getColor(context, R.color.route_done) and 0x00FFFFFF) or 0x4D000000 // 30 %
        // 1.4.0: a recorded trip shown from My trips (purple, under the route).
        add(LineLayer(LAYER_TRACK, SRC_TRACK).withProperties(
            PropertyFactory.lineColor(0xFF7B1FA2.toInt()), PropertyFactory.lineWidth(width(4f)), PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        // 1.4.0: all route lines 15 % wider.
        add(LineLayer(LAYER_ALT_CASING, SRC_ALT).withProperties(
            PropertyFactory.lineColor(casing), PropertyFactory.lineWidth(width(6.9f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.8f)
        ))
        add(LineLayer(LAYER_ALT, SRC_ALT).withProperties(
            PropertyFactory.lineColor(alt), PropertyFactory.lineWidth(width(4.6f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        add(LineLayer(LAYER_ROUTE_CASING, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(casing), PropertyFactory.lineWidth(width(9.2f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineGradient(gradient(casing, 0x00000000, doneFraction()))
        ))
        add(LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(primary), PropertyFactory.lineWidth(width(5.75f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineGradient(gradient(primary, doneColor, doneFraction()))
        ))
        // 1.5.1: the next turn, short, with an arrowhead: our red with a thin
        // black border on light maps, white with a red border on dark maps.
        val darkMap = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val turnFill = if (darkMap) 0xFFFFFFFF.toInt() else 0xFFB71C1C.toInt()
        val turnEdge = if (darkMap) 0xFFD32F2F.toInt() else 0xFF000000.toInt()
        add(LineLayer(LAYER_NEXT_EDGE, SRC_NEXT).withProperties(
            PropertyFactory.lineColor(turnEdge), PropertyFactory.lineWidth(width(5.0f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        add(LineLayer(LAYER_NEXT, SRC_NEXT).withProperties(
            PropertyFactory.lineColor(turnFill), PropertyFactory.lineWidth(width(3.2f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        ))
        add(SymbolLayer(LAYER_NEXT_HEAD, SRC_NEXT).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        ))
        // 1.1.0: the stretch already driven, darker and half transparent.
        add(LineLayer(LAYER_DONE, SRC_DONE).withProperties(
            PropertyFactory.lineColor(ContextCompat.getColor(context, R.color.route_done)), PropertyFactory.lineWidth(width(5.75f)),
            PropertyFactory.lineOpacity(0.3f),
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
            PropertyFactory.circleStrokeWidth(2f),
            // 1.0.7: places of the routes not selected are dimmed.
            PropertyFactory.circleOpacity(dimmed()),
            PropertyFactory.circleStrokeOpacity(dimmed())
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
            // 1.4.0: VersaTiles styles name their fonts differently.
            PropertyFactory.textFont(arrayOf(labelFont)),
            PropertyFactory.textSize(11f),
            PropertyFactory.textOpacity(dimmed()),
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
            PropertyFactory.iconOpacity(dimmed()),
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
        // 1.4.0: the sight being announced: a blinking red ring.
        add(CircleLayer(LAYER_FOCUS, SRC_FOCUS).withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor(0x00000000),
            PropertyFactory.circleStrokeColor(0xFFE51401.toInt()),
            PropertyFactory.circleStrokeWidth(3.5f),
            PropertyFactory.circleStrokeOpacity(1f)
        ))
        pushAll()
        pushExtras()
    }

    // ---------- 1.4.0: fade at the arrow, next turn, focus, recorded trip ----------

    private var routeColor = 0
    private var casingColor = 0
    private var doneColor = 0
    /** Mercator lengths of the selected route (for line-progress). */
    private var merc: DoubleArray? = null
    private var mercRoute: RouteResult? = null

    private fun doneFraction(): Double {
        val r = routes.getOrNull(selected) ?: return 0.0
        val d = doneM ?: return 0.0
        if (mercRoute !== r) { merc = com.backpack.app.core.LineProgress.mercatorCumulative(r.points); mercRoute = r }
        return com.backpack.app.core.LineProgress.fraction(r.line, merc ?: return 0.0, d)
    }

    /** Before the fraction: [before] (faint or invisible); from it on: [after]. */
    private fun gradient(after: Int, before: Int, frac: Double): Expression =
        if (frac <= 0.0) Expression.step(Expression.lineProgress(), Expression.color(after), *emptyArray<Expression.Stop>())
        else Expression.step(Expression.lineProgress(), Expression.color(before), Expression.stop(frac, Expression.color(after)))

    private fun pushFade() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val f = doneFraction()
        s.getLayer(LAYER_ROUTE)?.setProperties(PropertyFactory.lineGradient(gradient(routeColor, doneColor, f)))
        s.getLayer(LAYER_ROUTE_CASING)?.setProperties(PropertyFactory.lineGradient(gradient(casingColor, 0x00000000, f)))
    }

    private var nextTurn: List<LatLon>? = null
    private var focus: LatLon? = null
    private var track: List<LatLon>? = null

    /** The stretch around the next maneuver (null = none). */
    fun setNextTurn(points: List<LatLon>?) {
        if (points == nextTurn) return
        nextTurn = points
        pushExtras()
    }

    fun setFocus(p: LatLon?) {
        focus = p
        pushExtras()
    }

    /** Blink: the ring shown / hidden. */
    fun setFocusVisible(on: Boolean) {
        style?.takeIf { it.isFullyLoaded }?.getLayer(LAYER_FOCUS)?.setProperties(PropertyFactory.circleStrokeOpacity(if (on) 1f else 0f))
    }

    fun setTrack(points: List<LatLon>?) {
        track = points
        pushExtras()
    }

    private fun pushExtras() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        fun ls(p: List<LatLon>) = LineString.fromLngLats(p.map { Point.fromLngLat(it.lon, it.lat) })
        val n = nextTurn
        val nextFeatures = if (n == null || n.size < 2) emptyList() else {
            val a = n[n.size - 2]
            val b = n.last()
            listOf(Feature.fromGeometry(ls(n)), Feature.fromGeometry(Point.fromLngLat(b.lon, b.lat)).also {
                it.addStringProperty("icon", ICON_TURN)
                it.addNumberProperty("bearing", com.backpack.app.core.Geo.bearing(a, b))
            })
        }
        s.getSourceAs<GeoJsonSource>(SRC_NEXT)?.setGeoJson(FeatureCollection.fromFeatures(nextFeatures))
        val f = focus
        s.getSourceAs<GeoJsonSource>(SRC_FOCUS)?.setGeoJson(FeatureCollection.fromFeatures(
            if (f == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(f.lon, f.lat)))))
        val t = track
        s.getSourceAs<GeoJsonSource>(SRC_TRACK)?.setGeoJson(FeatureCollection.fromFeatures(
            if (t == null || t.size < 2) emptyList() else listOf(Feature.fromGeometry(ls(t)))))
    }

    /** Metres of the selected route already driven (navigation), or null. */
    private var doneM: Double? = null

    /** 1.1.0: navigation progress; the line splits into driven / ahead.
     *  Redrawn only every 15 m (the line has thousands of points). */
    fun setProgress(alongM: Double?, onRoute: RouteResult? = null) {
        // Only for the line drawn (a progress on another route is ignored).
        if (alongM != null && onRoute !== routes.getOrNull(selected)) return
        val old = doneM
        if (alongM == null && old == null) return
        // 1.4.0: only a colour change now (cheap): every 5 m, right at the arrow.
        if (alongM != null && old != null && Math.abs(alongM - old) < 5.0) return
        doneM = alongM
        pushFade()
    }

    fun setRoutes(routes: List<RouteResult>, selectedIndex: Int, letters: Boolean = routes.size > 1) {
        if (routes.getOrNull(selectedIndex) !== this.routes.getOrNull(selected)) doneM = null
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

    /** 1.1.0: id of the pin (start, stop, destination) under the finger, or null. */
    fun pinAt(map: MapLibreMap, screen: PointF): String? {
        val r = 18f * context.resources.displayMetrics.density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, LAYER_PINS).firstOrNull()?.getStringProperty("id")
    }

    fun setPins(pins: List<Pin>) {
        this.pins = pins
        pushPoints(SRC_PINS, pins)
    }

    fun setPois(pois: List<Pin>) {
        this.pois = pois
        pushPoints(SRC_POI, pois)
    }

    fun setTourist(list: List<TouristPoi>, dim: Set<String> = emptySet()) {
        tourist = list
        touristDim = dim
        pushTourist()
    }

    private var touristDim: Set<String> = emptySet()

    /** 1 for a normal place, 0.35 for a dimmed one. */
    private fun dimmed() = Expression.interpolate(
        Expression.linear(), Expression.toNumber(Expression.get("dim")),
        Expression.stop(0, 1f), Expression.stop(1, 0.35f)
    )

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
                it.addNumberProperty("dim", if (p.key in touristDim) 1 else 0)
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
        val r = 20f * context.resources.displayMetrics.density
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

    /** Selected route: the part ahead (blue) and, while navigating, the part
     *  already driven (darker, 50 %). Only these two sources are redrawn. */
    private fun pushRouteLine() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val primary = routes.getOrNull(selected)
        fun ls(p: List<LatLon>) = LineString.fromLngLats(p.map { Point.fromLngLat(it.lon, it.lat) })
        // 1.4.0: the whole line; the part behind you fades through the gradient.
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(
            FeatureCollection.fromFeatures(if (primary == null || primary.points.size < 2) emptyList() else listOf(Feature.fromGeometry(ls(primary.points))))
        )
        s.getSourceAs<GeoJsonSource>(SRC_DONE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        pushFade()
    }

    private fun pushRoutes() {
        val s = style ?: return
        if (!s.isFullyLoaded) return
        val altFeatures = routes.withIndex().filter { it.index != selected }.map { (i, r) ->
            Feature.fromGeometry(line(r)).also { it.addNumberProperty("idx", i) }
        }
        s.getSourceAs<GeoJsonSource>(SRC_ALT)?.setGeoJson(FeatureCollection.fromFeatures(altFeatures))
        pushRouteLine()
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
    /** True when the tap touches the selected (red) route line. */
    fun selectedRouteAt(map: MapLibreMap, screen: PointF): Boolean {
        val r = 20f * context.resources.displayMetrics.density
        val box = RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        return map.queryRenderedFeatures(box, LAYER_ROUTE, LAYER_ROUTE_CASING).isNotEmpty()
    }

    fun letterAt(map: MapLibreMap, screen: PointF): Int? {
        val r = 20f * context.resources.displayMetrics.density
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
                it.addNumberProperty("dim", if (p.dim) 1 else 0)
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
        const val ICON_TURN = "turn_head"
        private const val SRC_TRACK = "bp-track"
        private const val LAYER_TRACK = "bp-track-layer"
        private const val SRC_NEXT = "bp-next-turn"
        private const val LAYER_NEXT = "bp-next-turn-line"
        private const val LAYER_NEXT_EDGE = "bp-next-turn-edge"
        private const val LAYER_NEXT_HEAD = "bp-next-turn-head"
        private const val SRC_FOCUS = "bp-focus"
        private const val LAYER_FOCUS = "bp-focus-ring"

        private const val SRC_ALT = "bp-alt-routes"
        private const val SRC_ROUTE = "bp-route"
        private const val SRC_DONE = "bp-route-done"
        private const val LAYER_DONE = "bp-route-done-layer"
        private const val SRC_POI = "bp-poi"
        private const val SRC_PINS = "bp-pins"
        private const val SRC_WARN = "bp-warn"
        private const val SRC_TOUR = "bp-tourist"
        private const val SRC_LABELS = "bp-route-letters"
        private const val LAYER_LABELS = "bp-route-letters-layer"
        private const val LAYER_TOUR = "bp-tourist-dots"
        private const val LAYER_TOUR_LABEL = "bp-tourist-labels"

        val CAT_COLORS = mapOf(
            // 1.4.0: ochre, like the cave paintings.
            TouristCategory.PREHISTORY to 0xFFD2691E.toInt(),
            TouristCategory.SIGHT to 0xFF8A0000.toInt(),
            TouristCategory.MUSEUM to 0xFF6A1B9A.toInt(),
            TouristCategory.HISTORIC to 0xFF8D5524.toInt(),
            TouristCategory.VIEWPOINT to 0xFF00796B.toInt(),
            TouristCategory.NATURE to 0xFF2E7D32.toInt(),
            // 1.1.0
            TouristCategory.WATER to 0xFF0277BD.toInt(),
            TouristCategory.PARK to 0xFF33691E.toInt(),
            TouristCategory.HERITAGE to 0xFFB8860B.toInt()
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
