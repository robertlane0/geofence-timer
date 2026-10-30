package com.geofenceoffline.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.data.Geofence
import com.geofenceoffline.data.GeofenceRepository
import com.geofenceoffline.data.GeofenceType
import com.geofenceoffline.data.MapArea
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.databinding.FragmentGeofencesBinding
import com.geofenceoffline.databinding.ItemGeofenceBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GeofencesFragment : Fragment(), Screen {
    private var _binding: FragmentGeofencesBinding? = null
    private val binding get() = _binding!!

    private val geofenceRepository by lazy { GeofenceRepository(requireContext()) }
    private val mapRepository by lazy { MapRepository(requireContext()) }
    private var fences: List<Geofence> = emptyList()
    private var maps: List<MapArea> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeofencesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fabNewGeofence.setOnClickListener { startNewFence() }
        binding.emptyOpenMap.setOnClickListener { startNewFence() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                geofenceRepository.listAll() to mapRepository.listAll()
            }
            if (_binding == null) return@launch
            fences = loaded.first
            maps = loaded.second
            render()
        }
    }

    private fun render() {
        binding.geofencesContainer.removeAllViews()
        val hasFences = fences.isNotEmpty()
        binding.geofencesEmpty.isVisible = !hasFences
        fences.forEach { fence ->
            val item = ItemGeofenceBinding.inflate(layoutInflater, binding.geofencesContainer, false)
            item.geofenceName.text = fence.name
            item.geofenceIcon.setImageResource(
                if (fence.type == GeofenceType.CIRCLE) R.drawable.ic_circle else R.drawable.ic_polygon
            )
            item.geofenceDetails.text = buildString {
                append(fence.description())
                append(" · ")
                append(if (fence.enabled) "Enabled" else "Disabled")
            }
            item.root.alpha = if (fence.enabled) 1f else 0.65f
            item.geofenceSwitch.setOnCheckedChangeListener(null)
            item.geofenceSwitch.isChecked = fence.enabled
            item.geofenceSwitch.setOnCheckedChangeListener { _, checked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        geofenceRepository.setEnabled(fence.id, checked)
                    }
                    reload()
                }
            }
            item.root.setOnClickListener { openFenceOnMap(fence) }
            item.geofenceMore.setOnClickListener { showMenu(it, fence) }
            binding.geofencesContainer.addView(item.root)
        }
    }

    private fun showMenu(anchor: View, fence: Geofence) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_OPEN, 0, "Open on map")
        popup.menu.add(0, MENU_RENAME, 1, "Rename")
        popup.menu.add(0, MENU_DELETE, 2, "Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_OPEN -> {
                    openFenceOnMap(fence)
                    true
                }
                MENU_RENAME -> {
                    renameDialog(fence)
                    true
                }
                MENU_DELETE -> {
                    confirmDelete(fence)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun startNewFence() {
        if (maps.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Download a map first")
                .setMessage(
                    "Geofences are drawn on downloaded OpenStreetMap data. Download a small area " +
                        "once, then create fences offline as often as you like."
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.download_map) { _, _ ->
                    (activity as? MainActivity)?.openDownloadMap()
                }
                .show()
            return
        }
        val latest = maps.first()
        (activity as? MainActivity)?.openMap(latest.id, enterCreation = true)
    }

    private fun openFenceOnMap(fence: Geofence) {
        if (maps.isEmpty()) {
            mapRequiredDialog()
            return
        }
        val bounds = fence.bounds()
        val candidates = if (bounds == null) {
            maps
        } else {
            maps.filter { it.bounds.intersects(bounds) }
        }
        val target = candidates.firstOrNull { map ->
            bounds?.let { map.bounds.contains(it.center) } == true
        } ?: candidates.firstOrNull()
        if (target == null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("No map covers this geofence")
                .setMessage(
                    "The downloaded map containing this fence was deleted. Download that area " +
                        "again to view or edit the fence shape."
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.download_map) { _, _ ->
                    (activity as? MainActivity)?.openDownloadMap()
                }
                .show()
            return
        }
        (activity as? MainActivity)?.openMap(target.id, selectedGeofenceId = fence.id)
    }

    private fun mapRequiredDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download a map first")
            .setMessage("Open a downloaded map to view this geofence.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download_map) { _, _ ->
                (activity as? MainActivity)?.openDownloadMap()
            }
            .show()
    }

    private fun renameDialog(fence: Geofence) {
        val context = requireContext()
        val input = TextInputEditText(context).apply {
            setText(fence.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text?.length ?: 0)
        }
        val wrapper = TextInputLayout(context).apply {
            hint = "Geofence name"
            addView(input)
        }
        val container = FrameLayout(context).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(wrapper)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Rename geofence")
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        geofenceRepository.save(fence.copy(name = name, updatedAt = System.currentTimeMillis()))
                    }
                    reload()
                }
            }
            .show()
    }

    private fun confirmDelete(fence: Geofence) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete \u201c${fence.name}\u201d?")
            .setMessage(
                "This removes the fence and its schedule associations. Existing check history is " +
                    "kept. This cannot be undone."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { geofenceRepository.delete(fence.id) }
                    Toast.makeText(requireContext(), "Geofence deleted", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String = getString(R.string.geofences)

    companion object {
        private const val MENU_OPEN = 1
        private const val MENU_RENAME = 2
        private const val MENU_DELETE = 3
    }
}
