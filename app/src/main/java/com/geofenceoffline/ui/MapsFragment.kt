package com.geofenceoffline.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.data.MapArea
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.databinding.FragmentMapsBinding
import com.geofenceoffline.databinding.ItemMapBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MapsFragment : Fragment(), Screen {
    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { MapRepository(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fabDownload.setOnClickListener { (activity as MainActivity).openDownloadMap() }
        binding.emptyDownload.setOnClickListener { (activity as MainActivity).openDownloadMap() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val maps = withContext(Dispatchers.IO) { repository.listAll() }
            if (_binding == null) return@launch
            render(maps)
        }
    }

    private fun render(maps: List<MapArea>) {
        binding.mapsContainer.removeAllViews()
        val hasMaps = maps.isNotEmpty()
        binding.emptyState.isVisible = !hasMaps
        binding.storageRow.isVisible = hasMaps
        if (hasMaps) {
            val total = maps.sumOf { it.sizeBytes }
            binding.storageText.text =
                "${Formats.countLabel(maps.size, "map")} stored offline · ${Formats.bytes(total)}"
        }
        maps.forEach { area ->
            val item = ItemMapBinding.inflate(layoutInflater, binding.mapsContainer, false)
            item.mapName.text = area.name
            item.mapMeta.text = "${area.areaLabel()} · ${Formats.bytes(area.sizeBytes)}"
            item.mapDetails.text = "${area.featureCount} map features · downloaded ${Formats.date(area.downloadedAt)}"
            item.mapView.setOnClickListener {
                (activity as MainActivity).openMap(area.id)
            }
            item.mapDelete.setOnClickListener { confirmDelete(area) }
            item.root.setOnLongClickListener {
                renameDialog(area)
                true
            }
            binding.mapsContainer.addView(item.root)
        }
    }

    private fun confirmDelete(area: MapArea) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete \u201c${area.name}\u201d?")
            .setMessage("The offline map data will be removed from this device. Geofences and history are kept.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.delete(area.id) }
                    Toast.makeText(requireContext(), "Map deleted", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .show()
    }

    private fun renameDialog(area: MapArea) {
        val input = TextInputEditText(requireContext()).apply {
            setText(area.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text?.length ?: 0)
        }
        val wrapper = TextInputLayout(requireContext()).apply {
            hint = "Map name"
            addView(input)
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(wrapper)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename map")
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repository.rename(area.id, name) }
                        reload()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String = getString(R.string.downloaded_maps)
}
