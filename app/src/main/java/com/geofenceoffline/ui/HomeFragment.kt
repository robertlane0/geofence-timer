package com.geofenceoffline.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.geofenceoffline.R
import com.geofenceoffline.data.GeofenceRepository
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.data.ScheduleRepository
import com.geofenceoffline.databinding.FragmentHomeBinding

class HomeFragment : Fragment(), Screen {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.cardMaps.setOnClickListener { (activity as MainActivity).openMaps() }
        binding.cardGeofences.setOnClickListener { (activity as MainActivity).openGeofences() }
        binding.cardSchedules.setOnClickListener { (activity as MainActivity).openSchedules() }
        binding.cardHistory.setOnClickListener { (activity as MainActivity).openHistory() }
        binding.cardSettings.setOnClickListener { (activity as MainActivity).openSettings() }
        binding.setupButton.setOnClickListener { (activity as MainActivity).openSettings() }
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        val maps = MapRepository(context).listAll()
        val fences = GeofenceRepository(context).listAll()
        val schedules = ScheduleRepository(context).listAll()
        binding.summaryText.text = if (maps.isEmpty() && fences.isEmpty() && schedules.isEmpty()) {
            "Start by downloading a small offline map."
        } else {
            "${Formats.countLabel(maps.size, "offline map")} · " +
                "${Formats.countLabel(fences.size, "geofence")} · " +
                Formats.countLabel(schedules.size, "schedule")
        }

        val missing = PermissionTools.missingForReliableChecks(context)
        val notificationsMissing = !PermissionTools.hasNotificationPermission(context)
        binding.setupCard.visibility = if (missing.isNotEmpty() || notificationsMissing) View.VISIBLE else View.GONE
        if (missing.isNotEmpty()) {
            binding.setupText.text = "Missing: ${missing.joinToString(", ").lowercase()}. " +
                "Scheduled checks may not run reliably."
            binding.setupButton.text = "Review permissions"
        } else if (notificationsMissing) {
            binding.setupText.text = "Notifications are off. Checks still run, but result summaries stay in History."
            binding.setupButton.text = "Review notifications"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String = getString(R.string.app_name)

    override fun usesDrawer(): Boolean = true
}
