package com.geofenceoffline.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.geofenceoffline.data.Geofence
import com.geofenceoffline.data.GeofenceType
import com.geofenceoffline.geo.BoundingBox
import com.geofenceoffline.geo.GeoMath
import com.geofenceoffline.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator (EPSG:3857) helpers. World coordinates are normalised to 0..1. */
object WebMercator {
    fun x(lon: Double): Double = (lon + 180.0) / 360.0

    fun y(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
    }

    fun lon(x: Double): Double = x * 360.0 - 180.0

    fun lat(y: Double): Double {
        val n = PI * (1.0 - 2.0 * y.coerceIn(0.0, 1.0))
        return Math.toDegrees(atan(sinh(n)))
    }
}

/**
 * A self-contained offline vector map. All drawing uses the downloaded OSM data; no tile server is
 * contacted. The same widget is also used for the Overpass preview during area selection.
 */
class OfflineMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onMapTapped(point: GeoPoint)
        fun onMapLongPressed(point: GeoPoint)
        fun onCameraChanged(center: GeoPoint, zoom: Float)
        fun onSelectionChanged(bounds: BoundingBox)
    }

    var listener: Listener? = null

    private var mapData: OsmMapData? = null
    private var mapBounds: BoundingBox? = null
    private var geofences: List<Geofence> = emptyList()
    private var selectedGeofenceId: String? = null
    private var draftCircle: Pair<GeoPoint, Double>? = null
    private var draftPolygon: List<GeoPoint> = emptyList()
    private var draftColor = COLOR_BLUE
    private var loading = false
    private var emptyTitle: String? = "No offline map loaded"
    private var emptySubtitle: String? = "Download a map area to begin."
    private var previewMode = false
    private var showMapBounds = false

    private var centerX = WebMercator.x(-122.4194)
    private var centerY = WebMercator.y(37.7749)
    private var zoom = 15.5f
    private val minZoom = 2.0f
    private val maxZoom = 20.0f

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = value * scaledDensity

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LAND
        style = Paint.Style.FILL
    }
    private val waterPaint = fillPaint(COLOR_WATER)
    private val greenPaint = fillPaint(COLOR_GREEN)
    private val buildingPaint = fillPaint(COLOR_BUILDING)
    private val otherAreaPaint = fillPaint(COLOR_OTHER_AREA)
    private val waterLinePaint = strokePaint(COLOR_WATER_LINE, dp(1.5f))
    private val railPaint = strokePaint(COLOR_RAIL, dp(1.4f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val pathPaint = strokePaint(COLOR_PATH, dp(1.2f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val boundaryPaint = strokePaint(COLOR_BOUNDARY, dp(1f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
    }
    private val mapBoundsPaint = strokePaint(COLOR_BLUE, dp(1.5f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
        alpha = 130
    }
    private val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 47, 125, 225)
        style = Paint.Style.FILL
    }
    private val selectionStroke = strokePaint(COLOR_BLUE, dp(2f))
    private val handleFill = fillPaint(Color.WHITE)
    private val handleStroke = strokePaint(COLOR_BLUE, dp(2.5f))

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private var selectionEnabled = false
    private val selectionRect = RectF()
    private var selectionInitialised = false
    private var draggingHandle: Int? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            flingScroller.forceFinished(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = pixelsPerWorld()
            val oldCenterX = centerX
            val oldCenterY = centerY
            val focusX = detector.focusX
            val focusY = detector.focusY
            val worldFocusX = oldCenterX + (focusX - width / 2f) / oldScale
            val worldFocusY = oldCenterY + (focusY - height / 2f) / oldScale
            zoom = (zoom + log2(detector.scaleFactor.toDouble()).toFloat()).coerceIn(minZoom, maxZoom)
            val newScale = pixelsPerWorld()
            centerX = worldFocusX - (focusX - width / 2f) / newScale
            centerY = worldFocusY - (focusY - height / 2f) / newScale
            constrainCamera()
            notifyCamera()
            invalidate()
            return true
        }
    })

    private val flingScroller = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            flingScroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (scaleDetector.isInProgress) return false
            if (e1 == null) return false
            val scale = pixelsPerWorld()
            if (selectionEnabled && draggingHandle != null) return false
            centerX += distanceX / scale
            centerY += distanceY / scale
            constrainCamera()
            notifyCamera()
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val point = screenToGeo(e.x, e.y) ?: return false
            listener?.onMapTapped(point)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAround(e.x, e.y, 1.4f)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val point = screenToGeo(e.x, e.y) ?: return
            listener?.onMapLongPressed(point)
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (scaleDetector.isInProgress || selectionEnabled) return false
            if (e1 == null) return false
            lastFlingX = 0
            lastFlingY = 0
            flingScroller.forceFinished(true)
            flingScroller.fling(
                0, 0, velocityX.toInt(), velocityY.toInt(),
                -1_000_000, 1_000_000, -1_000_000, 1_000_000
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Offline map. Pan with one finger, pinch to zoom."
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setMapData(data: OsmMapData?, bounds: BoundingBox? = null, autoFit: Boolean = true) {
        mapData = data
        mapBounds = bounds
        if (data == null) {
            emptyTitle = if (emptyTitle == null) "No offline map loaded" else emptyTitle
        } else {
            emptyTitle = null
            emptySubtitle = null
        }
        if (data != null && autoFit && bounds != null) {
            fitBounds(bounds)
        }
        invalidate()
    }

    fun setEmptyMessage(title: String?, subtitle: String?) {
        emptyTitle = title
        emptySubtitle = subtitle
        invalidate()
    }

    fun setLoading(value: Boolean) {
        loading = value
        invalidate()
    }

    fun setPreviewMode(value: Boolean) {
        previewMode = value
        invalidate()
    }

    fun setShowMapBounds(value: Boolean) {
        showMapBounds = value
        invalidate()
    }

    fun setGeofences(list: List<Geofence>, selectedId: String? = null) {
        geofences = list
        selectedGeofenceId = selectedId
        invalidate()
    }

    fun setDraftCircle(center: GeoPoint?, radiusMeters: Double?) {
        draftCircle = if (center != null && radiusMeters != null && radiusMeters > 0) center to radiusMeters else null
        draftPolygon = emptyList()
        invalidate()
    }

    fun setDraftPolygon(points: List<GeoPoint>, color: Int = COLOR_BLUE) {
        draftPolygon = points.toList()
        draftCircle = null
        draftColor = color
        invalidate()
    }

    fun clearDraft() {
        draftCircle = null
        draftPolygon = emptyList()
        invalidate()
    }

    fun setSelectionEnabled(enabled: Boolean) {
        selectionEnabled = enabled
        if (enabled && !selectionInitialised && width > 0 && height > 0) {
            initialiseSelectionRect()
        }
        invalidate()
    }

    fun selectionBounds(): BoundingBox? {
        if (!selectionEnabled || width == 0 || height == 0) return null
        val topLeft = screenToGeoUnclamped(selectionRect.left, selectionRect.top) ?: return null
        val bottomRight = screenToGeoUnclamped(selectionRect.right, selectionRect.bottom) ?: return null
        return try {
            BoundingBox(
                south = min(topLeft.lat, bottomRight.lat).coerceAtLeast(-85.0),
                west = min(topLeft.lon, bottomRight.lon),
                north = max(topLeft.lat, bottomRight.lat).coerceAtMost(85.0),
                east = max(topLeft.lon, bottomRight.lon)
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun fitBounds(bounds: BoundingBox, horizontalPaddingDp: Float = 28f, verticalPaddingDp: Float = 28f) {
        if (width == 0 || height == 0) {
            post { fitBounds(bounds, horizontalPaddingDp, verticalPaddingDp) }
            return
        }
        val westX = WebMercator.x(bounds.west)
        val eastX = WebMercator.x(bounds.east)
        val northY = WebMercator.y(bounds.north)
        val southY = WebMercator.y(bounds.south)
        val worldW = max(1e-12, eastX - westX)
        val worldH = max(1e-12, southY - northY)
        val availableW = max(1f, width - dp(horizontalPaddingDp) * 2)
        val availableH = max(1f, height - dp(verticalPaddingDp) * 2)
        val scale = min(availableW / worldW, availableH / worldH)
        zoom = (log2(scale / 256.0)).toFloat().coerceIn(minZoom, maxZoom)
        centerX = (westX + eastX) / 2.0
        centerY = (northY + southY) / 2.0
        constrainCamera()
        notifyCamera()
        invalidate()
    }

    fun centerOn(point: GeoPoint, zoomLevel: Float = zoom) {
        centerX = WebMercator.x(point.lon)
        centerY = WebMercator.y(point.lat)
        zoom = zoomLevel.coerceIn(minZoom, maxZoom)
        constrainCamera()
        notifyCamera()
        invalidate()
    }

    fun zoomIn() = zoomAround(width / 2f, height / 2f, 1.5f)

    fun zoomOut() = zoomAround(width / 2f, height / 2f, 1f / 1.5f)

    fun currentZoom(): Float = zoom

    fun cameraCenter(): GeoPoint =
        GeoPoint(WebMercator.lat(centerY), WebMercator.lon(centerX))

    fun currentViewportBounds(): BoundingBox? {
        if (width == 0 || height == 0) return null
        val topLeft = screenToGeo(0f, 0f) ?: return null
        val bottomRight = screenToGeo(width.toFloat(), height.toFloat()) ?: return null
        return try {
            BoundingBox(
                min(topLeft.lat, bottomRight.lat),
                min(topLeft.lon, bottomRight.lon),
                max(topLeft.lat, bottomRight.lat),
                max(topLeft.lon, bottomRight.lon)
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun geoToScreen(point: GeoPoint): PointF {
        val scale = pixelsPerWorld()
        return PointF(
            ((WebMercator.x(point.lon) - centerX) * scale + width / 2.0).toFloat(),
            ((WebMercator.y(point.lat) - centerY) * scale + height / 2.0).toFloat()
        )
    }

    fun screenToGeo(x: Float, y: Float): GeoPoint? {
        val worldX = centerX + (x - width / 2f) / pixelsPerWorld()
        val worldY = centerY + (y - height / 2f) / pixelsPerWorld()
        if (worldY !in 0.00001..0.99999) return null
        val lon = ((WebMercator.lon(worldX) + 540.0) % 360.0) - 180.0
        val lat = WebMercator.lat(worldY)
        return runCatching { GeoPoint(lat, lon) }.getOrNull()
    }

    private fun screenToGeoUnclamped(x: Float, y: Float): GeoPoint? {
        val worldX = centerX + (x - width / 2f) / pixelsPerWorld()
        val worldY = (centerY + (y - height / 2f) / pixelsPerWorld()).coerceIn(0.00001, 0.99999)
        val lon = ((WebMercator.lon(worldX) + 540.0) % 360.0) - 180.0
        val lat = WebMercator.lat(worldY)
        return runCatching { GeoPoint(lat, lon) }.getOrNull()
    }

    private fun zoomAround(focusX: Float, focusY: Float, factor: Float) {
        val oldScale = pixelsPerWorld()
        val worldFocusX = centerX + (focusX - width / 2f) / oldScale
        val worldFocusY = centerY + (focusY - height / 2f) / oldScale
        zoom = (zoom + log2(factor.toDouble()).toFloat()).coerceIn(minZoom, maxZoom)
        val newScale = pixelsPerWorld()
        centerX = worldFocusX - (focusX - width / 2f) / newScale
        centerY = worldFocusY - (focusY - height / 2f) / newScale
        constrainCamera()
        notifyCamera()
        invalidate()
    }

    private fun pixelsPerWorld(): Double = 256.0 * 2.0.pow(zoom.toDouble())

    private fun constrainCamera() {
        centerX = ((centerX % 1.0) + 1.0) % 1.0
        centerY = centerY.coerceIn(0.0, 1.0)
    }

    private fun notifyCamera() {
        listener?.onCameraChanged(cameraCenter(), zoom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (selectionEnabled && !selectionInitialised) initialiseSelectionRect()
        if (selectionInitialised) {
            selectionRect.left = selectionRect.left.coerceIn(0f, w - dp(100f))
            selectionRect.top = selectionRect.top.coerceIn(0f, h - dp(100f))
            selectionRect.right = selectionRect.right.coerceIn(selectionRect.left + dp(80f), w.toFloat())
            selectionRect.bottom = selectionRect.bottom.coerceIn(selectionRect.top + dp(80f), h.toFloat())
        }
    }

    private fun initialiseSelectionRect() {
        if (width == 0 || height == 0) return
        val w = width * 0.76f
        val h = height * 0.55f
        selectionRect.set(
            (width - w) / 2f,
            (height - h) / 2f,
            (width + w) / 2f,
            (height + h) / 2f
        )
        selectionInitialised = true
        listener?.onSelectionChanged(selectionBounds() ?: return)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selectionEnabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val handle = findSelectionHandle(event.x, event.y)
                    if (handle != null) {
                        draggingHandle = handle
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingHandle != null) {
                        resizeSelection(event.x, event.y, draggingHandle!!)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (draggingHandle != null) {
                        draggingHandle = null
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun findSelectionHandle(x: Float, y: Float): Int? {
        val radius = dp(30f)
        val points = listOf(
            0 to PointF(selectionRect.left, selectionRect.top),
            1 to PointF(selectionRect.right, selectionRect.top),
            2 to PointF(selectionRect.right, selectionRect.bottom),
            3 to PointF(selectionRect.left, selectionRect.bottom)
        )
        return points.firstOrNull { (_, p) ->
            abs(p.x - x) <= radius && abs(p.y - y) <= radius
        }?.first
    }

    private fun resizeSelection(x: Float, y: Float, handle: Int) {
        val minSize = dp(72f)
        val cx = x.coerceIn(0f, width.toFloat())
        val cy = y.coerceIn(0f, height.toFloat())
        when (handle) {
            0 -> {
                selectionRect.left = cx.coerceAtMost(selectionRect.right - minSize)
                selectionRect.top = cy.coerceAtMost(selectionRect.bottom - minSize)
            }
            1 -> {
                selectionRect.right = cx.coerceAtLeast(selectionRect.left + minSize)
                selectionRect.top = cy.coerceAtMost(selectionRect.bottom - minSize)
            }
            2 -> {
                selectionRect.right = cx.coerceAtLeast(selectionRect.left + minSize)
                selectionRect.bottom = cy.coerceAtLeast(selectionRect.top + minSize)
            }
            3 -> {
                selectionRect.left = cx.coerceAtMost(selectionRect.right - minSize)
                selectionRect.bottom = cy.coerceAtLeast(selectionRect.top + minSize)
            }
        }
        selectionBounds()?.let { listener?.onSelectionChanged(it) }
        invalidate()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (flingScroller.computeScrollOffset()) {
            val dx = flingScroller.currX - lastFlingX
            val dy = flingScroller.currY - lastFlingY
            lastFlingX = flingScroller.currX
            lastFlingY = flingScroller.currY
            val scale = pixelsPerWorld()
            centerX -= dx / scale
            centerY -= dy / scale
            constrainCamera()
            notifyCamera()
            invalidate()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val data = mapData
        val viewport = RectF(-dp(32f), -dp(32f), width + dp(32f), height + dp(32f))

        if (data != null) {
            drawMap(canvas, data, viewport)
            drawMapLabels(canvas, data, viewport)
        }

        if (showMapBounds && mapBounds != null) {
            drawGeoBounds(canvas, mapBounds!!, mapBoundsPaint)
        }

        drawGeofences(canvas)
        drawDraft(canvas)

        if (selectionEnabled) drawSelection(canvas)

        drawScaleBar(canvas)
        drawAttribution(canvas)

        if (loading) {
            drawMessage(canvas, "Loading map…", null)
        } else if (data == null && emptyTitle != null) {
            drawMessage(canvas, emptyTitle!!, emptySubtitle)
        }

    }

    private fun drawMap(canvas: Canvas, data: OsmMapData, viewport: RectF) {
        val greens = ArrayList<OsmFeature>()
        val others = ArrayList<OsmFeature>()
        val waters = ArrayList<OsmFeature>()
        val buildings = ArrayList<OsmFeature>()
        val roads = ArrayList<OsmFeature>()
        val paths = ArrayList<OsmFeature>()
        val rails = ArrayList<OsmFeature>()
        val waterLines = ArrayList<OsmFeature>()
        val boundaries = ArrayList<OsmFeature>()

        data.features.forEach { feature ->
            if (!isVisible(feature.bounds, viewport)) return@forEach
            when {
                feature.isArea && isWaterArea(feature) -> waters.add(feature)
                feature.isArea && isGreenArea(feature) -> greens.add(feature)
                feature.isArea && feature.building != null -> buildings.add(feature)
                feature.isArea && (feature.landuse != null || feature.amenity != null ||
                    feature.leisure != null || feature.natural != null) -> others.add(feature)
                feature.isRoad -> roads.add(feature)
                feature.isPath -> paths.add(feature)
                feature.isRail -> rails.add(feature)
                feature.isWaterLine -> waterLines.add(feature)
                feature.tags.has("boundary") -> boundaries.add(feature)
            }
        }

        greens.forEach { drawArea(canvas, it, greenPaint) }
        others.forEach { drawArea(canvas, it, otherAreaPaint) }
        waters.forEach { drawArea(canvas, it, waterPaint) }
        buildings.forEach { drawArea(canvas, it, buildingPaint) }

        data.features.asSequence()
            .filter { isVisible(it.bounds, viewport) && it.isWaterLine }
            .forEach { drawPathFeature(canvas, it, waterLinePaint) }

        val mpp = GeoMath.metersPerPixel(cameraCenter().lat, zoom.toDouble())
        val roadOrder = roads.sortedBy { roadPriority(it) }
        val casingPaint = strokePaint(COLOR_ROAD_CASING, 1f)
        roadOrder.forEach { road ->
            val widthPx = max(dp(1.2f), roadWidthMeters(road) / mpp.toFloat())
            casingPaint.strokeWidth = widthPx + dp(1.8f)
            casingPaint.color = roadCasingColor(road)
            drawPathFeature(canvas, road, casingPaint)
        }
        roadOrder.forEach { road ->
            val widthPx = max(dp(1.2f), roadWidthMeters(road) / mpp.toFloat())
            val paint = strokePaint(roadFillColor(road), widthPx)
            drawPathFeature(canvas, road, paint)
        }

        rails.forEach { drawPathFeature(canvas, it, railPaint) }
        paths.forEach { drawPathFeature(canvas, it, pathPaint) }
        boundaries.forEach { drawPathFeature(canvas, it, boundaryPaint) }
    }

    private fun drawArea(canvas: Canvas, feature: OsmFeature, paint: Paint) {
        val path = featurePath(feature)
        canvas.drawPath(path, paint)
    }

    private fun drawPathFeature(canvas: Canvas, feature: OsmFeature, paint: Paint) {
        canvas.drawPath(featurePath(feature), paint)
    }

    private fun featurePath(feature: OsmFeature): Path {
        val path = Path()
        val scale = pixelsPerWorld()
        var started = false
        feature.points.forEach { point ->
            val x = ((WebMercator.x(point.lon) - centerX) * scale + width / 2.0).toFloat()
            val y = ((WebMercator.y(point.lat) - centerY) * scale + height / 2.0).toFloat()
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (feature.closed) path.close()
        return path
    }

    private fun drawMapLabels(canvas: Canvas, data: OsmMapData, viewport: RectF) {
        val occupied = ArrayList<RectF>()
        val mpp = GeoMath.metersPerPixel(cameraCenter().lat, zoom.toDouble())

        if (zoom >= 9f) {
            data.places.forEach { place ->
                val threshold = when (place.place) {
                    "city" -> 8f
                    "town" -> 10f
                    "village" -> 12f
                    "suburb", "neighbourhood" -> 13f
                    else -> 14f
                }
                if (zoom < threshold) return@forEach
                val p = geoToScreen(place.point)
                if (!viewport.contains(p.x, p.y)) return@forEach
                val size = when (place.place) {
                    "city" -> sp(15f)
                    "town" -> sp(13.5f)
                    else -> sp(11.5f)
                }
                drawLabel(
                    canvas, place.name, p.x, p.y, size,
                    if (place.place == "city" || place.place == "town") Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL),
                    occupied, maxWidth = width * 0.7f
                )
            }
        }

        if (zoom >= 14.5f) {
            val candidates = ArrayList<Pair<OsmFeature, PointF>>()
            data.features.forEach { feature ->
                if (!isVisible(feature.bounds, viewport)) return@forEach
                if (feature.name == null) return@forEach
                val isAreaLabel = feature.isArea &&
                    (isGreenArea(feature) || isWaterArea(feature) || feature.leisure != null)
                val isRoadLabel = feature.isRoad && zoom >= 16f
                val isPathLabel = feature.isPath && zoom >= 17.5f
                if (!isAreaLabel && !isRoadLabel && !isPathLabel) return@forEach
                val anchor = if (feature.isArea) areaAnchor(feature) else lineAnchor(feature)
                if (anchor == null) return@forEach
                val p = geoToScreen(anchor)
                if (!viewport.contains(p.x, p.y)) return@forEach
                candidates.add(feature to p)
            }
            candidates.sortedWith(
                compareBy<Pair<OsmFeature, PointF>> {
                    if (it.first.isArea) -1 else roadPriority(it.first)
                }.thenBy { it.first.id }
            ).take(70).forEach { (feature, p) ->
                val size = if (feature.isArea) sp(10.5f) else sp(10f)
                val name = feature.name ?: return@forEach
                drawLabel(
                    canvas, name, p.x, p.y, size, Typeface.create("sans-serif", Typeface.NORMAL), occupied,
                    maxWidth = min(width * 0.38f, dp(160f)),
                    color = if (isWaterArea(feature)) COLOR_WATER_LABEL else COLOR_TEXT_MUTED
                )
            }
        }
    }

    private fun drawLabel(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        typeface: Typeface,
        occupied: MutableList<RectF>,
        maxWidth: Float,
        color: Int = COLOR_TEXT
    ) {
        textPaint.textSize = size
        textHalo.textSize = size
        textPaint.typeface = typeface
        textHalo.typeface = typeface
        textPaint.color = color
        val measured = min(textPaint.measureText(text), maxWidth)
        val rect = RectF(x - measured / 2f, y - size, x + measured / 2f, y + dp(3f))
        if (occupied.any { RectF.intersects(it, rect) }) return
        occupied.add(rect)
        textPaint.textAlign = Paint.Align.CENTER
        textHalo.textAlign = Paint.Align.CENTER
        val drawText = if (textPaint.measureText(text) > maxWidth) ellipsize(text, maxWidth) else text
        val baseline = y - dp(2f)
        canvas.drawText(drawText, x, baseline, textHalo)
        canvas.drawText(drawText, x, baseline, textPaint)
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        var value = text
        while (value.length > 2 && textPaint.measureText("$value…") > maxWidth) {
            value = value.dropLast(1)
        }
        return "$value…"
    }

    private fun areaAnchor(feature: OsmFeature): GeoPoint? {
        if (feature.points.isEmpty()) return null
        return GeoPoint(
            feature.points.sumOf { it.lat } / feature.points.size,
            feature.points.sumOf { it.lon } / feature.points.size
        )
    }

    private fun polygonAnchor(points: List<GeoPoint>): GeoPoint? {
        if (points.isEmpty()) return null
        return GeoPoint(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)
    }

    private fun lineAnchor(feature: OsmFeature): GeoPoint? {
        if (feature.points.isEmpty()) return null
        val mid = feature.points.size / 2
        return feature.points[mid]
    }

    private fun drawGeofences(canvas: Canvas) {
        val ordered = geofences.sortedBy { if (it.id == selectedGeofenceId) 1 else 0 }
        ordered.forEach { fence ->
            val selected = fence.id == selectedGeofenceId
            val color = if (!fence.enabled) COLOR_DISABLED else colorForId(fence.id)
            val points = when (fence.type) {
                GeofenceType.CIRCLE -> fence.center?.let { center ->
                    GeoMath.circlePolygon(center, fence.radiusMeters ?: 0.0)
                }
                GeofenceType.POLYGON -> fence.polygon
            } ?: return@forEach
            if (points.size < 2) return@forEach
            val path = geoPolygonPath(points, close = true)
            val fill = Paint(fillPaint(color)).apply { alpha = if (selected) 48 else 30 }
            canvas.drawPath(path, fill)
            val stroke = strokePaint(color, if (selected) dp(4f) else dp(2.4f)).apply {
                if (!fence.enabled) {
                    pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
                }
            }
            canvas.drawPath(path, stroke)

            if (selected) {
                if (fence.type == GeofenceType.POLYGON) {
                    val handlePaint = fillPaint(Color.WHITE)
                    val handleStroke = strokePaint(color, dp(2f))
                    fence.polygon.forEach { vertex ->
                        val p = geoToScreen(vertex)
                        canvas.drawCircle(p.x, p.y, dp(4.5f), handlePaint)
                        canvas.drawCircle(p.x, p.y, dp(4.5f), handleStroke)
                    }
                } else if (fence.center != null) {
                    val p = geoToScreen(fence.center)
                    canvas.drawCircle(p.x, p.y, dp(5f), fillPaint(Color.WHITE))
                    canvas.drawCircle(p.x, p.y, dp(5f), strokePaint(color, dp(2f)))
                }
            }

            val anchor = when (fence.type) {
                GeofenceType.CIRCLE -> fence.center
                GeofenceType.POLYGON -> polygonAnchor(fence.polygon)
            }
            if (anchor != null) {
                val p = geoToScreen(anchor)
                val label = if (fence.enabled) fence.name else "${fence.name} (off)"
                drawFenceLabel(canvas, label, p.x, p.y, color)
            }
        }
    }

    private fun drawFenceLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
        textPaint.textSize = sp(10.5f)
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.color = COLOR_TEXT
        textPaint.textAlign = Paint.Align.CENTER
        val textWidth = textPaint.measureText(text)
        val rect = RectF(
            x - textWidth / 2f - dp(7f),
            y - dp(21f),
            x + textWidth / 2f + dp(7f),
            y - dp(6f)
        )
        canvas.drawRoundRect(rect, dp(4f), dp(4f), labelBackground)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), strokePaint(color, dp(1f)).apply { alpha = 160 })
        canvas.drawText(text, x, y - dp(10f), textPaint)
    }

    private fun drawDraft(canvas: Canvas) {
        draftCircle?.let { (center, radius) ->
            val path = geoPolygonPath(GeoMath.circlePolygon(center, radius), close = true)
            canvas.drawPath(path, Paint(fillPaint(draftColor)).apply { alpha = 42 })
            canvas.drawPath(path, strokePaint(draftColor, dp(2.5f)))
            val p = geoToScreen(center)
            canvas.drawCircle(p.x, p.y, dp(6f), fillPaint(Color.WHITE))
            canvas.drawCircle(p.x, p.y, dp(6f), strokePaint(draftColor, dp(2.5f)))
        }
        if (draftPolygon.isNotEmpty()) {
            val path = Path()
            draftPolygon.forEachIndexed { index, point ->
                val p = geoToScreen(point)
                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            if (draftPolygon.size >= 3) {
                path.close()
                canvas.drawPath(path, Paint(fillPaint(draftColor)).apply { alpha = 38 })
            }
            canvas.drawPath(path, strokePaint(draftColor, dp(2.5f)))
            draftPolygon.forEach { point ->
                val p = geoToScreen(point)
                canvas.drawCircle(p.x, p.y, dp(6.5f), fillPaint(Color.WHITE))
                canvas.drawCircle(p.x, p.y, dp(6.5f), strokePaint(draftColor, dp(2.5f)))
            }
        }
    }

    private fun drawSelection(canvas: Canvas) {
        canvas.drawRect(selectionRect, selectionFill)
        canvas.drawRect(selectionRect, selectionStroke)
        val radius = dp(9f)
        val centers = listOf(
            PointF(selectionRect.left, selectionRect.top),
            PointF(selectionRect.right, selectionRect.top),
            PointF(selectionRect.right, selectionRect.bottom),
            PointF(selectionRect.left, selectionRect.bottom)
        )
        centers.forEach { p ->
            canvas.drawCircle(p.x, p.y, radius, handleFill)
            canvas.drawCircle(p.x, p.y, radius, handleStroke)
        }
    }

    private fun drawGeoBounds(canvas: Canvas, bounds: BoundingBox, paint: Paint) {
        val topLeft = geoToScreen(GeoPoint(bounds.north, bounds.west))
        val bottomRight = geoToScreen(GeoPoint(bounds.south, bounds.east))
        canvas.drawRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y, paint)
    }

    private fun drawScaleBar(canvas: Canvas) {
        val mpp = GeoMath.metersPerPixel(cameraCenter().lat, zoom.toDouble())
        val target = dp(110f) * mpp
        val candidates = listOf(
            1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
            1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0,
            100_000.0, 200_000.0, 500_000.0, 1_000_000.0
        )
        val distance = candidates.minBy { abs(ln(it / target)) }
        val pixels = (distance / mpp).toFloat().coerceAtLeast(18f)
        val left = dp(14f)
        val bottom = height - dp(38f)
        val paint = strokePaint(Color.rgb(55, 65, 75), dp(2f))
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
        }
        val label = when {
            distance >= 1000 -> String.format(java.util.Locale.US, "%d km", (distance / 1000).toInt())
            else -> "${distance.toInt()} m"
        }
        canvas.drawRoundRect(
            left - dp(6f), bottom - dp(24f), left + pixels + dp(14f), bottom + dp(6f),
            dp(4f), dp(4f), background
        )
        textPaint.textSize = sp(10f)
        textPaint.color = Color.rgb(55, 65, 75)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, left + dp(3f), bottom - dp(10f), textPaint)
        canvas.drawLine(left, bottom, left + pixels, bottom, paint)
        canvas.drawLine(left, bottom - dp(4f), left, bottom + dp(2f), paint)
        canvas.drawLine(left + pixels, bottom - dp(4f), left + pixels, bottom + dp(2f), paint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawAttribution(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 100, 110)
            textSize = sp(8.5f)
            textAlign = Paint.Align.RIGHT
        }
        val text = "© OpenStreetMap contributors"
        val x = width - dp(8f)
        val y = height - dp(8f)
        val widthText = paint.measureText(text)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
        canvas.drawRoundRect(
            x - widthText - dp(4f), y - sp(9f), x + dp(2f), y + dp(3f),
            dp(3f), dp(3f), bg
        )
        canvas.drawText(text, x, y, paint)
    }

    private fun drawMessage(canvas: Canvas, title: String, subtitle: String?) {
        val centerX = width / 2f
        val centerY = height / 2f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = sp(14f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_MUTED
            textSize = sp(11.5f)
            textAlign = Paint.Align.CENTER
        }
        val boxWidth = min(width * 0.82f, max(titlePaint.measureText(title), subPaint.measureText(subtitle ?: "")) + dp(44f))
        val boxHeight = if (subtitle.isNullOrBlank()) dp(48f) else dp(66f)
        val box = RectF(
            centerX - boxWidth / 2f, centerY - boxHeight / 2f,
            centerX + boxWidth / 2f, centerY + boxHeight / 2f
        )
        canvas.drawRoundRect(box, dp(10f), dp(10f), labelBackground)
        canvas.drawText(title, centerX, centerY - if (subtitle.isNullOrBlank()) 0f else dp(8f), titlePaint)
        if (!subtitle.isNullOrBlank()) {
            canvas.drawText(subtitle, centerX, centerY + dp(14f), subPaint)
        }
    }

    private fun geoPolygonPath(points: List<GeoPoint>, close: Boolean): Path {
        val path = Path()
        points.forEachIndexed { index, point ->
            val p = geoToScreen(point)
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        if (close) path.close()
        return path
    }

    private fun isVisible(bounds: BoundingBox, viewport: RectF): Boolean {
        val scale = pixelsPerWorld()
        val x1 = ((WebMercator.x(bounds.west) - centerX) * scale + width / 2.0).toFloat()
        val x2 = ((WebMercator.x(bounds.east) - centerX) * scale + width / 2.0).toFloat()
        val y1 = ((WebMercator.y(bounds.north) - centerY) * scale + height / 2.0).toFloat()
        val y2 = ((WebMercator.y(bounds.south) - centerY) * scale + height / 2.0).toFloat()
        return x2 >= viewport.left && x1 <= viewport.right && y2 >= viewport.top && y1 <= viewport.bottom
    }

    private fun isWaterArea(feature: OsmFeature): Boolean =
        feature.natural == "water" || feature.waterway == "riverbank" ||
            feature.landuse == "reservoir" || feature.landuse == "basin"

    private fun isGreenArea(feature: OsmFeature): Boolean {
        val natural = feature.natural
        val landuse = feature.landuse
        val leisure = feature.leisure
        return natural in setOf("wood", "scrub", "grassland", "heath", "meadow", "wetland", "grass") ||
            landuse in setOf("forest", "grass", "meadow", "recreation_ground", "orchard", "vineyard", "cemetery") ||
            leisure in setOf("park", "garden", "playground", "pitch", "nature_reserve")
    }

    private fun roadWidthMeters(feature: OsmFeature): Float = when (feature.highway) {
        "motorway", "motorway_link" -> 24f
        "trunk", "trunk_link" -> 20f
        "primary", "primary_link" -> 17f
        "secondary", "secondary_link" -> 14f
        "tertiary", "tertiary_link" -> 12f
        "residential", "unclassified", "living_street" -> 8f
        "service" -> 5f
        "pedestrian" -> 6f
        "track" -> 3f
        "cycleway" -> 2.5f
        "footway", "path", "steps" -> 1.8f
        else -> 4f
    }

    private fun roadPriority(feature: OsmFeature): Int = when (feature.highway) {
        "motorway", "motorway_link" -> 0
        "trunk", "trunk_link" -> 1
        "primary", "primary_link" -> 2
        "secondary", "secondary_link" -> 3
        "tertiary", "tertiary_link" -> 4
        "residential", "unclassified", "living_street" -> 5
        "service", "pedestrian", "track" -> 6
        else -> 7
    }

    private fun roadFillColor(feature: OsmFeature): Int = when (feature.highway) {
        "motorway", "motorway_link" -> Color.rgb(242, 161, 136)
        "trunk", "trunk_link" -> Color.rgb(246, 181, 148)
        "primary", "primary_link" -> Color.rgb(249, 208, 154)
        "secondary", "secondary_link" -> Color.rgb(249, 230, 183)
        "tertiary", "tertiary_link" -> Color.WHITE
        "service" -> Color.rgb(245, 243, 239)
        else -> Color.WHITE
    }

    private fun roadCasingColor(feature: OsmFeature): Int = when (feature.highway) {
        "motorway", "motorway_link" -> Color.rgb(199, 103, 83)
        "trunk", "trunk_link" -> Color.rgb(203, 120, 72)
        "primary", "primary_link" -> Color.rgb(201, 154, 84)
        "secondary", "secondary_link" -> Color.rgb(190, 169, 115)
        else -> Color.rgb(190, 185, 178)
    }

    private fun colorForId(id: String): Int = FENCE_COLORS[(id.hashCode() and 0x7FFFFFFF) % FENCE_COLORS.size]

    private fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    companion object {
        val COLOR_BLUE = Color.rgb(47, 125, 225)
        val COLOR_TEXT = Color.rgb(28, 48, 65)
        val COLOR_TEXT_MUTED = Color.rgb(100, 112, 124)
        val COLOR_DISABLED = Color.rgb(150, 156, 162)
        private val COLOR_LAND = Color.rgb(242, 239, 232)
        private val COLOR_WATER = Color.rgb(170, 211, 223)
        private val COLOR_WATER_LINE = Color.rgb(126, 181, 210)
        private val COLOR_WATER_LABEL = Color.rgb(64, 115, 140)
        private val COLOR_GREEN = Color.rgb(205, 235, 176)
        private val COLOR_BUILDING = Color.rgb(222, 214, 206)
        private val COLOR_OTHER_AREA = Color.rgb(236, 233, 226)
        private val COLOR_ROAD_CASING = Color.rgb(190, 185, 178)
        private val COLOR_RAIL = Color.rgb(130, 130, 130)
        private val COLOR_PATH = Color.rgb(176, 141, 106)
        private val COLOR_BOUNDARY = Color.rgb(155, 145, 165)
        private val FENCE_COLORS = intArrayOf(
            Color.rgb(47, 125, 225),
            Color.rgb(34, 164, 93),
            Color.rgb(232, 143, 31),
            Color.rgb(130, 90, 190),
            Color.rgb(211, 72, 72)
        )
    }
}
