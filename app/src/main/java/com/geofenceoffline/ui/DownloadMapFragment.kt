package com.geofenceoffline.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.data.MapArea
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.databinding.FragmentDownloadMapBinding
import com.geofenceoffline.geo.BoundingBox
import com.geofenceoffline.geo.GeoPoint
import com.geofenceoffline.map.DownloadProgress
import com.geofenceoffline.map.DownloadStage
import com.geofenceoffline.map.MapDownloadException
import com.geofenceoffline.map.MapDownloader
import com.geofenceoffline.map.MapLimits
import com.geofenceoffline.map.MapPreviewLoader
import com.geofenceoffline.map.MapSizeEstimator
import com.geofenceoffline.map.OsmMapData
import com.geofenceoffline.map.OfflineMapView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class DownloadMapFragment : Fragment(), Screen {
    private var _binding: FragmentDownloadMapBinding? = null
    private val binding get() = _binding!!

    private val mapRepository by lazy { MapRepository(requireContext()) }
    private val previewLoader by lazy { MapPreviewLoader(requireContext()) }

    private var selection: BoundingBox? = null
    private var previewBounds: BoundingBox? = null
    private var previewBytes: Long? = null
    private var previewIsFullData = false
    private var previewJob: Job? = null
    private var downloadJob: Job? = null
    private var cancelLocation: (() -> Unit)? = null
    private var lastArea: MapArea? = null
    private var autoName = ""

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            locate()
        } else {
            toast("Location permission is needed to center the map on your position.")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mapPreview.setPreviewMode(true)
        binding.mapPreview.setEmptyMessage(
            "Choose an area",
            "Load OpenStreetMap context, then drag the box corners."
        )
        binding.mapPreview.listener = object : OfflineMapView.Listener {
            override fun onMapTapped(point: GeoPoint) = Unit
            override fun onMapLongPressed(point: GeoPoint) = Unit

            override fun onCameraChanged(center: GeoPoint, zoom: Float) {
                if (_binding == null) return
                selection = binding.mapPreview.selectionBounds()
                selection?.let(::updateSelectionInfo)
                val bounds = previewBounds
                if (bounds != null && !bounds.contains(center)) {
                    setPreviewStatus(R.drawable.bg_dot_amber, "Panned outside preview")
                }
            }

            override fun onSelectionChanged(bounds: BoundingBox) {
                selection = bounds
                updateSelectionInfo(bounds)
            }
        }
        binding.mapPreview.setSelectionEnabled(true)

        binding.btnZoomIn.setOnClickListener { binding.mapPreview.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.mapPreview.zoomOut() }
        binding.btnLocate.setOnClickListener { requestLocation() }
        binding.btnLoadPreview.setOnClickListener {
            if (previewJob?.isActive == true) {
                previewJob?.cancel()
                setPreviewLoading(false)
                setPreviewStatus(R.drawable.bg_dot_amber, "Preview cancelled")
                binding.selectionEstimate.text = "Preview cancelled. Tap Load preview to retry."
            } else {
                loadPreview(auto = false)
            }
        }
        binding.btnChangeCenter.setOnClickListener { showCoordinateDialog() }
        binding.btnDownload.setOnClickListener { confirmDownload() }
        binding.btnCancelDownload.setOnClickListener { cancelDownload() }
        binding.btnDone.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnViewMap.setOnClickListener {
            val area = lastArea ?: return@setOnClickListener
            parentFragmentManager.popBackStack()
            (activity as? MainActivity)?.openMap(area.id)
        }
        binding.mapNameInput.setText("")
        binding.selectionArea.text = "Move the map and resize the box."
        binding.selectionEstimate.text = "Loading map context…"

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.progressContainer.isVisible) {
                        confirmCancelDownload()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        binding.mapPreview.post { initialiseMapContext() }
    }

    private fun initialiseMapContext() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                mapRepository.listAll().firstOrNull()
            }
            if (_binding == null) return@launch
            if (latest != null) {
                val data = withContext(Dispatchers.IO) {
                    MapSizeEstimator.preloadFile(mapRepository.fileFor(latest))
                }
                if (_binding == null) return@launch
                if (data != null) {
                    previewBounds = latest.bounds
                    previewBytes = data.sourceBytes
                    previewIsFullData = true
                    binding.mapPreview.setMapData(data, latest.bounds, autoFit = true)
                    setPreviewStatus(
                        R.drawable.bg_dot_green,
                        "Offline context · ${latest.name}"
                    )
                    setPreviewLoading(false)
                    selection = binding.mapPreview.selectionBounds()
                    selection?.let(::updateSelectionInfo)
                    return@launch
                }
            }
            loadPreview(auto = true)
        }
    }

    private fun updateSelectionInfo(bounds: BoundingBox) {
        if (_binding == null) return
        selection = bounds
        val dimensions = "${Formats.area(bounds.areaKm2)} · " +
            "${bounds.widthMeters.toInt()} × ${bounds.heightMeters.toInt()} m"
        binding.selectionArea.text = dimensions
        binding.centerText.text = String.format(
            Locale.US, "%.5f, %.5f", bounds.center.lat, bounds.center.lon
        )
        binding.selectionBox.text = String.format(
            Locale.US,
            "S %.5f  W %.5f\nN %.5f  E %.5f",
            bounds.south, bounds.west, bounds.north, bounds.east
        )

        val estimate = estimateBytes(bounds)
        val tooLarge = MapLimits.exceeds(bounds)
        binding.selectionEstimate.text = if (tooLarge) {
            "Selection is too large for one download. Make the box smaller."
        } else {
            "Estimated download: ${Formats.bytes(estimate)}"
        }
        binding.selectionEstimate.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (tooLarge) R.color.red else R.color.text_secondary
            )
        )
        binding.selectionHint.text = when {
            tooLarge -> "Limit: ${MapLimits.MAX_AREA_KM2.toInt()} km² and " +
                "${MapLimits.MAX_DIMENSION_METERS.toInt()} m per side."
            previewBytes == null -> "Load a preview to improve the size estimate."
            else -> "Estimate only. Roads, paths, buildings and land use are included."
        }

        val auto = "Map near %.5f, %.5f".format(
            Locale.US, bounds.center.lat, bounds.center.lon
        )
        if (binding.mapNameInput.text.isNullOrBlank() || binding.mapNameInput.text.toString() == autoName) {
            binding.mapNameInput.setText(auto)
            autoName = auto
        }
    }

    private fun estimateBytes(bounds: BoundingBox): Long {
        val base = MapSizeEstimator.estimateBytes(
            bounds,
            previewBytes,
            previewBounds
        )
        val adjusted = if (previewBytes != null && !previewIsFullData) {
            (base * 2.2).toLong()
        } else {
            base
        }
        return adjusted.coerceIn(20_000L, 29_500_000L)
    }

    private fun loadPreview(auto: Boolean) {
        if (previewJob?.isActive == true) return
        val selected = selection
        val queryBounds = if (selected != null) {
            capPreviewBounds(selected.expandedBy(PREVIEW_MARGIN_METERS))
        } else {
            val viewport = binding.mapPreview.currentViewportBounds()
            if (viewport == null) {
                toast("Move the map first.")
                return
            }
            capPreviewBounds(viewport)
        }

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            setPreviewLoading(true)
            setPreviewStatus(R.drawable.bg_dot_amber, "Loading preview…")
            binding.previewProgressText.text = "Loading OpenStreetMap context…"
            try {
                val data = previewLoader.load(queryBounds) { downloaded, total ->
                    if (_binding == null) return@load
                    val text = if (total != null && total > 0) {
                        "Loading preview · ${Formats.bytes(downloaded)} of ${Formats.bytes(total)}"
                    } else {
                        "Loading preview · ${Formats.bytes(downloaded)}"
                    }
                    binding.root.post { if (_binding != null) binding.previewProgressText.text = text }
                }
                if (_binding == null) return@launch
                previewBounds = queryBounds
                previewBytes = data.sourceBytes
                previewIsFullData = false
                binding.mapPreview.setMapData(data, queryBounds, autoFit = false)
                setPreviewStatus(R.drawable.bg_dot_green, "Preview · OpenStreetMap")
                selection = binding.mapPreview.selectionBounds()
                selection?.let(::updateSelectionInfo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                setPreviewStatus(R.drawable.bg_dot_red, "Preview unavailable")
                if (!auto) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Could not load preview")
                        .setMessage(e.message ?: "Check your internet connection and try again.")
                        .setPositiveButton("Try again") { _, _ -> loadPreview(auto = false) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    binding.selectionEstimate.text = "Preview unavailable. Tap Load preview to retry."
                    binding.selectionHint.text = "You can still enter coordinates or use your location."
                }
            } finally {
                if (_binding != null) setPreviewLoading(false)
            }
        }
    }


    /** Keeps the preview request small and respectful of the public Overpass service. */
    private fun capPreviewBounds(bounds: BoundingBox): BoundingBox {
        val maxWidth = 8_000.0
        val maxHeight = 8_000.0
        val center = bounds.center
        val latSpan = min(bounds.north - bounds.south, maxHeight / 110_574.0)
        val lonSpan = min(bounds.east - bounds.west, maxWidth / (111_320.0 * max(0.02, kotlin.math.abs(kotlin.math.cos(Math.toRadians(center.lat))))))
        return BoundingBox(
            south = max(-85.0, center.lat - latSpan / 2.0),
            west = max(-180.0, center.lon - lonSpan / 2.0),
            north = min(85.0, center.lat + latSpan / 2.0),
            east = min(180.0, center.lon + lonSpan / 2.0)
        )
    }

    private fun setPreviewLoading(loading: Boolean) {
        if (_binding == null) return
        binding.previewProgressCard.isVisible = loading
        binding.btnLoadPreview.isEnabled = true
        binding.mapPreview.setLoading(loading)
        binding.btnLoadPreview.text = if (loading) "Cancel preview" else "Load preview"
    }

    private fun setPreviewStatus(dotDrawable: Int, text: String) {
        if (_binding == null) return
        binding.previewDot.setBackgroundResource(dotDrawable)
        binding.previewStatus.text = text
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
        setPreviewStatus(R.drawable.bg_dot_amber, "Finding your location…")
        cancelLocation = UiLocationHelper.requestCurrentLocation(requireContext()) { location ->
            if (_binding == null) return@requestCurrentLocation
            setPreviewStatus(
                if (location == null) R.drawable.bg_dot_red else R.drawable.bg_dot_green,
                if (location == null) "Location unavailable" else "Centered on your location"
            )
            if (location != null) {
                binding.mapPreview.centerOn(
                    GeoPoint(location.latitude, location.longitude),
                    16f
                )
                selection = binding.mapPreview.selectionBounds()
                selection?.let(::updateSelectionInfo)
            } else {
                toast("No location fix available. Try again or load a preview.")
            }
        }
    }

    private fun showCoordinateDialog() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "37.7749, -122.4194"
            setText(
                selection?.center?.let {
                    String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon)
                } ?: ""
            )
            setSelection(text?.length ?: 0)
        }
        val wrapper = TextInputLayout(context).apply {
            hint = "Latitude, longitude"
            helperText = "WGS84 decimal degrees. Used only to move the map."
            addView(input)
        }
        val container = FrameLayout(context).apply {
            val padding = (20 * density).toInt()
            setPadding(padding, padding / 3, padding, 0)
            addView(wrapper)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Enter coordinates")
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Move map", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parts = input.text.toString()
                    .trim()
                    .split(Regex("[,;\\s]+"))
                    .filter { it.isNotBlank() }
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat == null || lon == null || lat !in -85.0..85.0 || lon !in -180.0..180.0) {
                    wrapper.error = "Enter a latitude between -85 and 85 and a longitude between -180 and 180."
                    return@setOnClickListener
                }
                dialog.dismiss()
                binding.mapPreview.centerOn(GeoPoint(lat, lon), 16f)
                selection = binding.mapPreview.selectionBounds()
                selection?.let(::updateSelectionInfo)
            }
        }
        dialog.show()
    }

    private fun confirmDownload() {
        val bounds = selection ?: binding.mapPreview.selectionBounds()
        if (bounds == null) {
            toast("Select an area on the map first.")
            return
        }
        val name = binding.mapNameInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.nameLayout.error = "Enter a name for this map."
            return
        }
        binding.nameLayout.error = null
        if (MapLimits.exceeds(bounds)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Area too large")
                .setMessage(
                    "This app is designed for small, street-level extracts. Keep the selection " +
                        "inside ${MapLimits.MAX_AREA_KM2.toInt()} km² and " +
                        "${MapLimits.MAX_DIMENSION_METERS.toInt()} m per side so the Overpass " +
                        "service and offline renderer stay responsive."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val estimate = estimateBytes(bounds)
        val warning = if (estimate > 15_000_000L) {
            "\n\nThis is a large download. A smaller area will open faster."
        } else {
            ""
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download this area?")
            .setMessage(
                "\u201c$name\u201d\n" +
                    "${Formats.area(bounds.areaKm2)} · approximately ${Formats.bytes(estimate)}\n\n" +
                    "Data comes from OpenStreetMap through overpass.private.coffee. " +
                    "After this download, the map is rendered entirely on this device and " +
                    "never contacts a tile server.$warning"
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Download") { _, _ -> startDownload(name, bounds) }
            .show()
    }

    private fun startDownload(name: String, bounds: BoundingBox) {
        binding.formScroll.isVisible = false
        binding.successContainer.isVisible = false
        binding.progressContainer.isVisible = true
        binding.btnCancelDownload.isEnabled = true
        binding.downloadTitle.text = "Downloading map data…"
        binding.downloadSubtitle.text = "Preparing request"
        binding.downloadPercentText.text = ""
        binding.downloadProgress.isIndeterminate = true
        renderStages(DownloadStage.PREPARING)

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val area = MapDownloader(requireContext()).download(name, bounds) { progress ->
                    if (_binding == null) return@download
                    binding.root.post { if (_binding != null) renderProgress(progress) }
                }
                if (_binding == null) return@launch
                lastArea = area
                showSuccess(area)
            } catch (e: CancellationException) {
                if (_binding != null) {
                    showForm()
                    toast("Download cancelled")
                }
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                showForm()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Download failed")
                    .setMessage(
                        (e as? MapDownloadException)?.message
                            ?: "The map could not be downloaded. Check your connection and try again."
                    )
                    .setPositiveButton("Try again") { _, _ -> confirmDownload() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun renderProgress(progress: DownloadProgress) {
        renderStages(progress.stage)
        when (progress.stage) {
            DownloadStage.PREPARING -> {
                binding.downloadPercentText.text = "…"
                binding.downloadSubtitle.text = "Building a small Overpass request"
                binding.downloadProgress.isIndeterminate = true
            }
            DownloadStage.FETCHING -> {
                val total = progress.totalBytes
                if (total != null && total > 0) {
                    val percent = ((progress.downloadedBytes * 100) / total).toInt().coerceIn(0, 100)
                    binding.downloadProgress.isIndeterminate = false
                    binding.downloadProgress.setProgressCompat(percent, true)
                    binding.downloadPercentText.text = "$percent%"
                    binding.downloadSubtitle.text =
                        "${Formats.bytes(progress.downloadedBytes)} of ${Formats.bytes(total)}"
                } else {
                    binding.downloadProgress.isIndeterminate = true
                    binding.downloadPercentText.text = Formats.bytes(progress.downloadedBytes)
                    binding.downloadSubtitle.text = "Receiving OpenStreetMap data"
                }
            }
            DownloadStage.PARSING -> {
                binding.downloadProgress.isIndeterminate = true
                binding.downloadPercentText.text = "Reading data"
                binding.downloadSubtitle.text = "Validating nodes, ways and features"
            }
            DownloadStage.SAVING -> {
                binding.downloadProgress.isIndeterminate = true
                binding.downloadPercentText.text = "Almost done"
                binding.downloadSubtitle.text = "Saving to private app storage"
            }
        }
    }

    private fun renderStages(stage: DownloadStage) {
        val current = when (stage) {
            DownloadStage.PREPARING -> 0
            DownloadStage.FETCHING -> 1
            DownloadStage.PARSING -> 2
            DownloadStage.SAVING -> 3
        }
        val icons = listOf(
            binding.stageQueryIcon,
            binding.stageFetchIcon,
            binding.stageParseIcon,
            binding.stageSaveIcon
        )
        val texts = listOf(
            binding.stageQueryText,
            binding.stageFetchText,
            binding.stageParseText,
            binding.stageSaveText
        )
        icons.forEachIndexed { index, icon ->
            when {
                index < current -> {
                    icon.setImageResource(R.drawable.ic_check)
                    icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green))
                    texts[index].setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_secondary)
                    )
                }
                index == current -> {
                    icon.setImageResource(R.drawable.ic_circle)
                    icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.blue))
                    texts[index].setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_primary)
                    )
                }
                else -> {
                    icon.setImageResource(R.drawable.ic_circle)
                    icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text_muted))
                    texts[index].setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_muted)
                    )
                }
            }
        }
    }

    private fun showSuccess(area: MapArea) {
        binding.progressContainer.isVisible = false
        binding.successContainer.isVisible = true
        binding.successName.text = area.name
        binding.successMeta.text =
            "${area.areaLabel()} · ${Formats.bytes(area.sizeBytes)} · " +
                "${area.featureCount} features"
        binding.downloadProgress.isIndeterminate = true
        binding.btnCancelDownload.isEnabled = false
        setPreviewLoading(false)
    }

    private fun showForm() {
        if (_binding == null) return
        binding.progressContainer.isVisible = false
        binding.successContainer.isVisible = false
        binding.formScroll.isVisible = true
        binding.downloadProgress.isIndeterminate = true
    }

    private fun cancelDownload() {
        if (downloadJob?.isActive == true) {
            confirmCancelDownload()
        } else {
            showForm()
        }
    }

    private fun confirmCancelDownload() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Cancel download?")
            .setMessage("The partial download will be discarded. Nothing is saved.")
            .setNegativeButton("Keep downloading", null)
            .setPositiveButton("Cancel download") { _, _ ->
                downloadJob?.cancel()
                showForm()
            }
            .show()
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

    override fun screenTitle(): String = getString(R.string.download_map_area)

    companion object {
        private const val PREVIEW_MARGIN_METERS = 350.0
    }
}
