package com.geofenceoffline.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.checks.AlarmScheduler
import com.geofenceoffline.data.CheckHistoryRepository
import com.geofenceoffline.data.GeofenceRepository
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.data.ScheduleRepository
import com.geofenceoffline.data.SettingsRepository
import com.geofenceoffline.databinding.FragmentSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment(), Screen {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val settingsRepository by lazy { SettingsRepository(requireContext()) }
    private val mapRepository by lazy { MapRepository(requireContext()) }
    private val historyRepository by lazy { CheckHistoryRepository(requireContext()) }
    private val scheduleRepository by lazy { ScheduleRepository(requireContext()) }
    private val geofenceRepository by lazy { GeofenceRepository(requireContext()) }

    private var updatingControls = false
    private var currentSettings = com.geofenceoffline.data.AppSettings(50, 90, 15, true)

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast("Precise location permission was not granted.")
        }
        refresh()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast("Notifications stay off. Check results are still stored in History.")
        }
        refresh()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindPermissions()
        bindSettings()
        bindData()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun bindPermissions() {
        binding.permLocationButton.setOnClickListener {
            if (PermissionTools.hasFineLocation(requireContext())) {
                PermissionTools.openAppSettings(requireContext())
            } else {
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        binding.permBackgroundButton.setOnClickListener {
            if (!PermissionTools.hasFineLocation(requireContext())) {
                toast("Grant precise location first.")
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else if (!PermissionTools.hasBackgroundLocation(requireContext()) &&
                Build.VERSION.SDK_INT >= 29
            ) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Allow background location")
                    .setMessage(
                        "Android requires background location to be enabled from app settings. " +
                            "Choose Permissions → Location → Allow all the time. Without it, " +
                            "scheduled checks cannot read a fix while the app is closed."
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("Open settings") { _, _ ->
                        PermissionTools.openBackgroundLocationSettings(requireContext())
                    }
                    .show()
            } else {
                PermissionTools.openBackgroundLocationSettings(requireContext())
            }
        }
        binding.permNotificationsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && !PermissionTools.hasNotificationPermission(requireContext())) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionTools.openNotificationSettings(requireContext())
            }
        }
        binding.permExactButton.setOnClickListener {
            PermissionTools.openExactAlarmSettings(requireContext())
        }
        binding.permBatteryButton.setOnClickListener {
            if (PermissionTools.isIgnoringBatteryOptimizations(requireContext())) {
                PermissionTools.openAppSettings(requireContext())
            } else {
                PermissionTools.requestIgnoreBatteryOptimizations(requireContext())
            }
        }
    }

    private fun bindSettings() {
        binding.accuracySeek.max = 495
        binding.accuracySeek.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingControls) return
                val meters = progress + 5
                binding.accuracyValue.text = "$meters m"
                if (fromUser) settingsRepository.setAccuracyThreshold(meters)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
        })

        binding.lateGraceSeek.max = 119
        binding.lateGraceSeek.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingControls) return
                val minutes = progress + 1
                binding.lateGraceValue.text = "$minutes min"
                if (fromUser) settingsRepository.setLateGraceMinutes(minutes)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
        })

        binding.timeoutRow.setOnClickListener { showTimeoutDialog() }
        binding.notifySwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingControls) settingsRepository.setNotifyResults(checked)
        }
    }

    private fun bindData() {
        binding.dataMapsRow.setOnClickListener { (activity as? MainActivity)?.openMaps() }
        binding.dataHistoryRow.setOnClickListener { (activity as? MainActivity)?.openHistory() }
        binding.dataDeleteRow.setOnClickListener { confirmDeleteAll() }
    }

    private fun refresh() {
        if (_binding == null) return
        refreshPermissions()
        currentSettings = settingsRepository.load()
        updatingControls = true
        binding.accuracySeek.progress = (currentSettings.accuracyThresholdMeters - 5).coerceIn(0, 495)
        binding.accuracyValue.text = "${currentSettings.accuracyThresholdMeters} m"
        binding.lateGraceSeek.progress = (currentSettings.lateGraceMinutes - 1).coerceIn(0, 119)
        binding.lateGraceValue.text = "${currentSettings.lateGraceMinutes} min"
        binding.timeoutValue.text = "${currentSettings.locationTimeoutSeconds} s"
        binding.notifySwitch.isChecked = currentSettings.notifyResults
        updatingControls = false
        refreshDataSummaries()
    }

    private fun refreshPermissions() {
        val context = requireContext()
        val fine = PermissionTools.hasFineLocation(context)
        val background = PermissionTools.hasBackgroundLocation(context)
        val notifications = PermissionTools.hasNotificationPermission(context)
        val exact = PermissionTools.canScheduleExactAlarms(context)
        val batteryIgnored = PermissionTools.isIgnoringBatteryOptimizations(context)

        setPermissionStatus(
            binding.permLocationStatus,
            if (fine) "Granted · precise location" else "Not granted · checks cannot read a fix",
            fine
        )
        binding.permLocationButton.text = if (fine) "Settings" else "Allow"

        val backgroundText = when {
            background -> "Granted · works while the app is closed"
            Build.VERSION.SDK_INT < 29 -> "Granted with precise location"
            !fine -> "Grant precise location first"
            else -> "Not granted · scheduled checks need this"
        }
        setPermissionStatus(binding.permBackgroundStatus, backgroundText, background)
        binding.permBackgroundButton.text = if (background) "Settings" else "Open settings"

        setPermissionStatus(
            binding.permNotificationsStatus,
            if (notifications) "On · private result summaries enabled"
            else "Off · results still appear in History",
            notifications,
            warningOnly = true
        )
        binding.permNotificationsButton.text = if (notifications) "Settings" else "Allow"

        val exactText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (exact) "Granted · checks can start on time"
            else "Not granted · checks may be delayed"
        } else {
            "Available on this Android version"
        }
        setPermissionStatus(binding.permExactStatus, exactText, exact)
        binding.permExactButton.text = if (exact) "Manage" else "Allow"

        setPermissionStatus(
            binding.permBatteryStatus,
            if (batteryIgnored) "Unrestricted · best for reliable checks"
            else "Optimized · Android may delay checks",
            batteryIgnored,
            warningOnly = true
        )
        binding.permBatteryButton.text = if (batteryIgnored) "Manage" else "Allow"
    }

    private fun setPermissionStatus(
        view: android.widget.TextView,
        text: String,
        ok: Boolean,
        warningOnly: Boolean = false
    ) {
        view.text = text
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    ok -> R.color.green
                    warningOnly -> R.color.amber
                    else -> R.color.red
                }
            )
        )
    }

    private fun refreshDataSummaries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                Triple(mapRepository.listAll(), historyRepository.count(), scheduleRepository.count())
            }
            if (_binding == null) return@launch
            val maps = data.first
            binding.dataMapsSummary.text =
                "${Formats.countLabel(maps.size, "map")} · ${Formats.bytes(maps.sumOf { it.sizeBytes })}"
            binding.dataHistorySummary.text =
                "${Formats.countLabel(data.second, "check")} · ${Formats.countLabel(data.third, "schedule")}"
        }
    }

    private fun showTimeoutDialog() {
        val values = intArrayOf(30, 45, 60, 90, 120, 180, 300)
        val labels = values.map { "$it seconds" }.toTypedArray()
        val current = currentSettings.locationTimeoutSeconds
        val selected = values.indexOf(current).takeIf { it >= 0 } ?: 3
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Location timeout")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                settingsRepository.setLocationTimeout(values[which])
                binding.timeoutValue.text = "${values[which]} s"
                currentSettings = currentSettings.copy(locationTimeoutSeconds = values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete all app data?")
            .setMessage(
                "This permanently deletes downloaded maps, geofences, schedules, pending alarms, " +
                    "all check history and preference settings from this device. Location permissions " +
                    "are managed by Android and are not changed. This cannot be undone."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Delete everything") { _, _ ->
                deleteAllData()
            }
            .show()
    }

    private fun deleteAllData() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val scheduler = AlarmScheduler(requireContext())
                scheduleRepository.listAll().forEach { scheduler.cancel(it.id) }
                historyRepository.deleteAll()
                scheduleRepository.listAll().forEach { scheduleRepository.delete(it.id) }
                geofenceRepository.listAll().forEach { geofenceRepository.delete(it.id) }
                mapRepository.deleteAll()
            }
            settingsRepository.setAccuracyThreshold(SettingsRepository.DEFAULT_ACCURACY)
            settingsRepository.setLocationTimeout(SettingsRepository.DEFAULT_TIMEOUT)
            settingsRepository.setLateGraceMinutes(SettingsRepository.DEFAULT_LATE_GRACE)
            settingsRepository.setNotifyResults(true)
            Toast.makeText(requireContext(), "All app data deleted", Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun toast(text: String) {
        if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String = getString(R.string.settings)
}
