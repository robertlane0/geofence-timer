package com.geofenceoffline.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.data.Geofence
import com.geofenceoffline.data.GeofenceRepository
import com.geofenceoffline.data.GeofenceType
import com.geofenceoffline.data.MapArea
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.databinding.FragmentMapBinding
import com.geofenceoffline.geo.GeoMath
import com.geofenceoffline.geo.GeoPoint
import com.geofenceoffline.map.MapSizeEstimator
import com.geofenceoffline.map.OfflineMapView
import com.geofenceoffline.map.OsmMapData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MapFragment : Fragment(), Screen {
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val mapRepository by lazy { MapRepository(requireContext()) }
    private val geofenceRepository by lazy { GeofenceRepository(requireContext()) }

    private var mapId: String = ""
    private var initialGeofenceId: String? = null
    private var enterCreation = false
    private var area: MapArea? = null
    private var fences: List<Geofence> = emptyList()
    private var selected: Geofence? = null
    private var mode = Mode.IDLE
    private var draftCenter: GeoPoint? = null
    private var draftRadius: Double = 100.0
    private val draftPoints = ArrayList<GeoPoint>()
    private var updatingRadius = false
    private var cancelLocation: (() -> Unit)? = null

    private enum class Mode {
        IDLE,
        VIEW,
        CREATE_CIRCLE,
        CREATE_POLYGON,
        EDIT_SHAPE
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) locate() else toast("Precise location permission is needed to center the map.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapId = arguments?.getString(ARG_MAP_ID).orEmpty()
        initialGeofenceId = arguments?.getString(ARG_GEOFENCE_ID)
        enterCreation = arguments?.getBoolean(ARG_ENTER_CREATION) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (mapId.isBlank()) {
            toast("No map selected.")
            parentFragmentManager.popBackStack()
            return
        }

        binding.mapView.setShowMapBounds(true)
        binding.mapView.setEmptyMessage("Loading offline map…", null)
        binding.mapView.listener = object : OfflineMapView.Listener {
            override fun onMapTapped(point: GeoPoint) = handleMapTap(point)
            override fun onMapLongPressed(point: GeoPoint) = handleLongPress(point)
            override fun onCameraChanged(center: GeoPoint, zoom: Float) = Unit
            override fun onSelectionChanged(bounds: com.geofenceoffline.geo.BoundingBox) = Unit
        }

        binding.mapZoomIn.setOnClickListener { binding.mapView.zoomIn() }
        binding.mapZoomOut.setOnClickListener { binding.mapView.zoomOut() }
        binding.mapLocate.setOnClickListener { requestLocation() }
        binding.btnNewCircle.setOnClickListener {
            showCreateCircle(binding.mapView.cameraCenter())
        }
        binding.btnNewPolygon.setOnClickListener { showCreatePolygon() }
        binding.btnManageFences.setOnClickListener {
            (activity as? MainActivity)?.openGeofences()
        }
        binding.btnEditShape.setOnClickListener { beginShapeEdit() }
        binding.btnUndoPoint.setOnClickListener { undoPoint() }
        binding.btnDeleteFence.setOnClickListener { confirmDelete() }
        binding.btnCancelEdit.setOnClickListener { cancelEditor() }
        binding.btnSaveFence.setOnClickListener { saveFence() }

        binding.radiusSeek.max = MAX_RADIUS_PROGRESS
        binding.radiusSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingRadius || !fromUser) return
                if (mode != Mode.CREATE_CIRCLE && !isEditingCircleShape()) return
                draftRadius = progressToRadius(progress)
                binding.radiusValue.text = formatRadius(draftRadius)
                syncDraft()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
        })

        loadMap()
    }

    private fun loadMap() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.mapLoading.isVisible = true
            val result = withContext(Dispatchers.IO) {
                val map = mapRepository.get(mapId)
                val file = map?.let { mapRepository.fileFor(it) }
                val data = if (file != null && file.exists()) {
                    MapSizeEstimator.preloadFile(file)
                } else {
                    null
                }
                Triple(map, map != null && file != null && file.exists(), data)
            }
            if (_binding == null) return@launch
            binding.mapLoading.isVisible = false
            val map = result.first
            val fileExists = result.second
            val data = result.third
            if (map == null) {
                failMap("This downloaded map no longer exists.")
                return@launch
            }
            area = map
            binding.mapTitleChip.text = "${map.name} · offline"
            (activity as? MainActivity)?.refreshAppBar()
            if (!fileExists || data == null) {
                failMap("The stored map data is missing or unreadable. Download this area again.")
                return@launch
            }
            binding.mapView.setMapData(data, map.bounds, autoFit = true)
            loadFencesAndInitialState()
        }
    }

    private fun failMap(message: String) {
        binding.mapView.setEmptyMessage("Map unavailable", message)
        binding.mapLoading.isVisible = false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Could not open map")
            .setMessage(message)
            .setPositiveButton("Back") { _, _ -> parentFragmentManager.popBackStack() }
            .setNegativeButton("Delete record") { _, _ ->
                val id = area?.id ?: mapId
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { mapRepository.delete(id) }
                    parentFragmentManager.popBackStack()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun loadFencesAndInitialState() {
        viewLifecycleOwner.lifecycleScope.launch {
            fences = withContext(Dispatchers.IO) { geofenceRepository.listAll() }
            if (_binding == null) return@launch
            val initial = initialGeofenceId?.let { id -> fences.firstOrNull { it.id == id } }
            initialGeofenceId = null
            when {
                initial != null -> {
                    showView(initial)
                    initial.bounds()?.let {
                        binding.mapView.fitBounds(it.expandedBy(120.0), 64f, 100f)
                    }
                }
                enterCreation -> {
                    enterCreation = false
                    binding.mapView.post {
                        if (_binding != null) showCreateCircle(binding.mapView.cameraCenter())
                    }
                }
                else -> showIdle()
            }
        }
    }

    private fun handleMapTap(point: GeoPoint) {
        when {
            mode == Mode.CREATE_CIRCLE || (mode == Mode.EDIT_SHAPE && selected?.type == GeofenceType.CIRCLE) -> {
                draftCenter = point
                syncDraft()
                binding.editorHint.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_muted)
                )
                binding.editorHint.text = "Centre moved. Set the radius below."
            }
            mode == Mode.CREATE_POLYGON || (mode == Mode.EDIT_SHAPE && selected?.type == GeofenceType.POLYGON) -> {
                if (draftPoints.isEmpty() ||
                    GeoMath.haversineMeters(draftPoints.last(), point) > 0.25
                ) {
                    draftPoints.add(point)
                    syncDraft()
                    binding.editorHint.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_muted)
                    )
                    binding.editorHint.text = if (draftPoints.size < 3) {
                        "${draftPoints.size} point${if (draftPoints.size == 1) "" else "s"} placed. Add at least 3."
                    } else {
                        "${draftPoints.size} points. Tap Save when the polygon is complete."
                    }
                }
            }
            mode == Mode.VIEW || mode == Mode.IDLE -> {
                val hit = findFenceAt(point)
                if (hit != null) {
                    showView(hit)
                } else if (mode == Mode.VIEW) {
                    showIdle()
                }
            }
        }
    }

    private fun handleLongPress(point: GeoPoint) {
        when (mode) {
            Mode.IDLE -> showCreateCircle(point)
            Mode.VIEW -> Unit
            Mode.CREATE_CIRCLE -> {
                draftCenter = point
                syncDraft()
            }
            Mode.CREATE_POLYGON -> {
                draftPoints.add(point)
                syncDraft()
            }
            Mode.EDIT_SHAPE -> {
                if (selected?.type == GeofenceType.CIRCLE) {
                    draftCenter = point
                } else {
                    draftPoints.add(point)
                }
                syncDraft()
            }
        }
    }

    private fun findFenceAt(point: GeoPoint): Geofence? =
        fences.filter { fence ->
            fence.bounds()?.contains(point) == true && fence.contains(point)
        }.minByOrNull { fence ->
            fence.bounds()?.areaKm2 ?: Double.MAX_VALUE
        }

    private fun showIdle() {
        mode = Mode.IDLE
        selected = null
        binding.panelIdle.isVisible = true
        binding.panelEditorScroll.isVisible = false
        binding.mapView.clearDraft()
        renderFences()
    }

    private fun showView(fence: Geofence) {
        mode = Mode.VIEW
        selected = fence
        binding.panelIdle.isVisible = false
        binding.panelEditorScroll.isVisible = true
        binding.editorTitle.text = "Edit geofence"
        binding.fenceNameLayout.error = null
        binding.fenceNameInput.setText(fence.name)
        binding.fenceEnabledSwitch.isChecked = fence.enabled
        binding.enabledContainer.isVisible = true
        binding.btnEditShape.isVisible = true
        binding.btnDeleteFence.isVisible = true
        binding.btnUndoPoint.isVisible = false
        binding.radiusContainer.isVisible = fence.type == GeofenceType.CIRCLE
        if (fence.type == GeofenceType.CIRCLE) {
            draftCenter = fence.center
            draftRadius = fence.radiusMeters ?: 100.0
            showRadius(draftRadius, enabled = false)
            binding.editorHint.text = "Tap Edit shape to move the centre or change the radius."
        } else {
            binding.editorHint.text =
                "${fence.polygon.size} vertices. Tap Edit shape to redraw the polygon."
        }
        resetHintColor()
        binding.mapView.clearDraft()
        renderFences()
    }

    private fun showCreateCircle(center: GeoPoint?) {
        mode = Mode.CREATE_CIRCLE
        selected = null
        draftCenter = center ?: binding.mapView.cameraCenter()
        draftRadius = 100.0
        binding.panelIdle.isVisible = false
        binding.panelEditorScroll.isVisible = true
        binding.editorTitle.text = "New circle geofence"
        binding.editorHint.text = "Tap the map to move the centre, then set a radius."
        resetHintColor()
        binding.fenceNameLayout.error = null
        binding.fenceNameInput.setText("Circle fence")
        binding.enabledContainer.isVisible = false
        binding.radiusContainer.isVisible = true
        binding.btnEditShape.isVisible = false
        binding.btnDeleteFence.isVisible = false
        binding.btnUndoPoint.isVisible = false
        showRadius(draftRadius, enabled = true)
        syncDraft()
        renderFences()
    }

    private fun showCreatePolygon() {
        mode = Mode.CREATE_POLYGON
        selected = null
        draftPoints.clear()
        draftCenter = null
        binding.panelIdle.isVisible = false
        binding.panelEditorScroll.isVisible = true
        binding.editorTitle.text = "New polygon geofence"
        binding.editorHint.text = "Tap at least three points on the map to draw a closed polygon."
        resetHintColor()
        binding.fenceNameLayout.error = null
        binding.fenceNameInput.setText("Polygon fence")
        binding.enabledContainer.isVisible = false
        binding.radiusContainer.isVisible = false
        binding.btnEditShape.isVisible = false
        binding.btnDeleteFence.isVisible = false
        binding.btnUndoPoint.isVisible = true
        syncDraft()
        renderFences()
    }

    private fun beginShapeEdit() {
        val fence = selected ?: return
        if (fence.type == GeofenceType.CIRCLE) {
            mode = Mode.EDIT_SHAPE
            draftCenter = fence.center
            draftRadius = fence.radiusMeters ?: 100.0
            binding.btnEditShape.isVisible = false
            binding.btnUndoPoint.isVisible = false
            binding.radiusContainer.isVisible = true
            showRadius(draftRadius, enabled = true)
            binding.editorTitle.text = "Move circle"
            binding.editorHint.text = "Tap the map to move the centre. Set the radius below."
            resetHintColor()
            syncDraft()
            renderFences()
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Redraw polygon?")
                .setMessage(
                    "The current shape stays until you save. Tap at least three new points, " +
                        "or cancel to keep the existing polygon."
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Start redrawing") { _, _ ->
                    mode = Mode.EDIT_SHAPE
                    draftPoints.clear()
                    binding.btnEditShape.isVisible = false
                    binding.btnUndoPoint.isVisible = true
                    binding.radiusContainer.isVisible = false
                    binding.editorTitle.text = "Redraw polygon"
                    binding.editorHint.text = "Tap at least three points to replace the polygon."
                    resetHintColor()
                    syncDraft()
                    renderFences()
                }
                .show()
        }
    }

    private fun isEditingCircleShape(): Boolean =
        mode == Mode.EDIT_SHAPE && selected?.type == GeofenceType.CIRCLE

    private fun syncDraft() {
        if (_binding == null) return
        when {
            mode == Mode.CREATE_CIRCLE ||
                (mode == Mode.EDIT_SHAPE && selected?.type == GeofenceType.CIRCLE) -> {
                binding.mapView.setDraftCircle(draftCenter, draftRadius)
            }
            mode == Mode.CREATE_POLYGON ||
                (mode == Mode.EDIT_SHAPE && selected?.type == GeofenceType.POLYGON) -> {
                binding.mapView.setDraftPolygon(draftPoints)
            }
            else -> binding.mapView.clearDraft()
        }
    }

    private fun renderFences() {
        if (_binding == null) return
        val visible = if (mode == Mode.EDIT_SHAPE && selected != null) {
            fences.filter { it.id != selected?.id }
        } else {
            fences
        }
        binding.mapView.setGeofences(visible, selected?.id.takeIf { mode != Mode.EDIT_SHAPE })
    }

    private fun showRadius(radius: Double, enabled: Boolean) {
        updatingRadius = true
        binding.radiusSeek.isEnabled = enabled
        binding.radiusSeek.progress = radiusToProgress(radius)
        binding.radiusValue.text = formatRadius(radius)
        updatingRadius = false
    }

    private fun radiusToProgress(radius: Double): Int =
        (radius / 10.0).roundToInt().coerceIn(1, MAX_RADIUS_PROGRESS + 1) - 1

    private fun progressToRadius(progress: Int): Double = (progress + 1) * 10.0

    private fun formatRadius(radius: Double): String = if (radius >= 1000) {
        String.format(Locale.US, "%.1f km", radius / 1000.0)
    } else {
        "${radius.roundToInt()} m"
    }

    private fun undoPoint() {
        if (draftPoints.isNotEmpty()) {
            draftPoints.removeAt(draftPoints.lastIndex)
            syncDraft()
            binding.editorHint.text = if (draftPoints.isEmpty()) {
                "Tap at least three points on the map."
            } else {
                "${draftPoints.size} points. Add at least 3."
            }
        }
    }

    private fun cancelEditor() {
        val fence = selected
        when (mode) {
            Mode.EDIT_SHAPE -> if (fence != null) {
                showView(fence)
            } else {
                showIdle()
            }
            Mode.VIEW -> showIdle()
            Mode.CREATE_CIRCLE, Mode.CREATE_POLYGON -> showIdle()
            Mode.IDLE -> Unit
        }
    }

    private fun saveFence() {
        val name = binding.fenceNameInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.fenceNameLayout.error = "Enter a geofence name."
            return
        }
        binding.fenceNameLayout.error = null
        val existing = selected
        val type = when {
            mode == Mode.CREATE_CIRCLE || (existing != null && existing.type == GeofenceType.CIRCLE) ->
                GeofenceType.CIRCLE
            else -> GeofenceType.POLYGON
        }
        val center: GeoPoint?
        val radius: Double?
        val polygon: List<GeoPoint>
        if (type == GeofenceType.CIRCLE) {
            center = if (mode == Mode.VIEW) existing?.center else draftCenter
            radius = if (mode == Mode.VIEW) existing?.radiusMeters else draftRadius
            polygon = emptyList()
            if (center == null || radius == null || radius <= 0.0) {
                editorError("Place the circle centre on the map.")
                return
            }
        } else {
            val points = if (mode == Mode.VIEW) existing?.polygon.orEmpty() else draftPoints.toList()
            if (points.size < 3) {
                editorError("A polygon needs at least three points.")
                return
            }
            if (!GeoMath.isSimplePolygon(points)) {
                editorError("The polygon edges cross or touch. Move a point and try again.")
                return
            }
            if (GeoMath.polygonAreaKm2(points) <= 0.00000001) {
                editorError("The polygon has no area. Place the points farther apart.")
                return
            }
            center = null
            radius = null
            polygon = points
        }

        val now = System.currentTimeMillis()
        val fence = Geofence(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            type = type,
            center = center,
            radiusMeters = radius,
            polygon = polygon,
            enabled = if (existing != null) binding.fenceEnabledSwitch.isChecked else true,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        binding.btnSaveFence.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val savedList = withContext(Dispatchers.IO) {
                geofenceRepository.save(fence)
                geofenceRepository.listAll()
            }
            if (_binding == null) return@launch
            binding.btnSaveFence.isEnabled = true
            fences = savedList
            val saved = savedList.firstOrNull { it.id == fence.id } ?: fence
            showView(saved)
            toast("Geofence saved")
        }
    }

    private fun editorError(message: String) {
        binding.editorHint.text = message
        binding.editorHint.setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
    }

    private fun resetHintColor() {
        binding.editorHint.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
    }

    private fun confirmDelete() {
        val fence = selected ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete \u201c${fence.name}\u201d?")
            .setMessage(
                "The geofence is removed from this device and from any schedules that use it. " +
                    "Existing history entries are kept."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val remaining = withContext(Dispatchers.IO) {
                        geofenceRepository.delete(fence.id)
                        geofenceRepository.listAll()
                    }
                    if (_binding == null) return@launch
                    fences = remaining
                    showIdle()
                    toast("Geofence deleted")
                }
            }
            .show()
    }

    private fun requestLocation() {
        if (UiLocationHelper.hasPermission(requireContext())) {
            locate()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun locate() {
        cancelLocation?.invoke()
        cancelLocation = UiLocationHelper.requestCurrentLocation(requireContext()) { location ->
            if (_binding == null) return@requestCurrentLocation
            if (location == null) {
                toast("No location fix available.")
            } else {
                binding.mapView.centerOn(
                    GeoPoint(location.latitude, location.longitude),
                    16f
                )
            }
        }
    }

    private fun toast(text: String) {
        if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        cancelLocation?.invoke()
        cancelLocation = null
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String = area?.name ?: getString(R.string.map_geofences)

    companion object {
        private const val ARG_MAP_ID = "map_id"
        private const val ARG_GEOFENCE_ID = "geofence_id"
        private const val ARG_ENTER_CREATION = "enter_creation"
        private const val MAX_RADIUS_PROGRESS = 200

        fun newInstance(
            mapId: String,
            selectedGeofenceId: String? = null,
            enterCreation: Boolean = false
        ): MapFragment = MapFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MAP_ID, mapId)
                putString(ARG_GEOFENCE_ID, selectedGeofenceId)
                putBoolean(ARG_ENTER_CREATION, enterCreation)
            }
        }
    }
}
