package com.geofenceoffline.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.checks.AlarmScheduler
import com.geofenceoffline.data.Geofence
import com.geofenceoffline.data.GeofenceRepository
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.data.Schedule
import com.geofenceoffline.data.ScheduleRepository
import com.geofenceoffline.data.ScheduleType
import com.geofenceoffline.databinding.FragmentScheduleEditBinding
import com.geofenceoffline.scheduling.SchedulePlanner
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.util.UUID

class ScheduleEditFragment : Fragment(), Screen {
    private var _binding: FragmentScheduleEditBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy { ScheduleRepository(requireContext()) }
    private val geofenceRepository by lazy { GeofenceRepository(requireContext()) }
    private val mapRepository by lazy { MapRepository(requireContext()) }

    private var scheduleId: String? = null
    private var existing: Schedule? = null
    private var loadedOnce = false
    private var fences: List<Geofence> = emptyList()

    private var type = ScheduleType.SPECIFIC_TIMES
    private val times = ArrayList<Int>()
    private var weekdays: MutableSet<DayOfWeek> = mutableSetOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    )
    private var windowStartMinutes = 16 * 60
    private var windowEndMinutes = 17 * 60
    private var intervalMinutes = 10
    private val selectedFenceIds = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleId = arguments?.getString(ARG_SCHEDULE_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduleEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWeekdayChips()
        setupTypeToggle()
        setupTimeAndWindowButtons()
        binding.btnCreateFence.setOnClickListener { createFence() }
        binding.btnSaveSchedule.setOnClickListener { save() }
        binding.scheduleEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            binding.enabledHint.text = if (checked) {
                "Next runs are registered with Android AlarmManager and restored after reboot."
            } else {
                "This schedule will not run until it is enabled."
            }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (loadedOnce) refreshFences()
    }

    private fun setupTypeToggle() {
        binding.typeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            type = if (checkedId == R.id.btn_type_window) {
                ScheduleType.REPEATING_WINDOW
            } else {
                ScheduleType.SPECIFIC_TIMES
            }
            renderType()
        }
    }

    private fun setupTimeAndWindowButtons() {
        binding.btnAddTime.setOnClickListener {
            showTimePicker("Add check time", times.firstOrNull() ?: (9 * 60)) { minute ->
                if (times.none { it == minute }) {
                    times.add(minute)
                    times.sort()
                    renderTimes()
                    binding.formError.isVisible = false
                }
            }
        }
        binding.btnWindowStart.setOnClickListener {
            showTimePicker("Window start", windowStartMinutes) { minute ->
                windowStartMinutes = minute
                binding.btnWindowStart.text = Schedule.formatMinute(minute)
            }
        }
        binding.btnWindowEnd.setOnClickListener {
            showTimePicker("Window end", windowEndMinutes) { minute ->
                windowEndMinutes = minute
                binding.btnWindowEnd.text = Schedule.formatMinute(minute)
            }
        }
    }

    private fun setupWeekdayChips() {
        val chips = mapOf(
            binding.chipMon to DayOfWeek.MONDAY,
            binding.chipTue to DayOfWeek.TUESDAY,
            binding.chipWed to DayOfWeek.WEDNESDAY,
            binding.chipThu to DayOfWeek.THURSDAY,
            binding.chipFri to DayOfWeek.FRIDAY,
            binding.chipSat to DayOfWeek.SATURDAY,
            binding.chipSun to DayOfWeek.SUNDAY
        )
        chips.forEach { (chip, day) ->
            chip.setOnCheckedChangeListener { button, checked ->
                if (checked) weekdays.add(day) else weekdays.remove(day)
                button.isChecked = checked
            }
        }
    }

    private fun selectWeekdays(days: Set<DayOfWeek>) {
        weekdays = days.toMutableSet()
        binding.chipMon.isChecked = DayOfWeek.MONDAY in days
        binding.chipTue.isChecked = DayOfWeek.TUESDAY in days
        binding.chipWed.isChecked = DayOfWeek.WEDNESDAY in days
        binding.chipThu.isChecked = DayOfWeek.THURSDAY in days
        binding.chipFri.isChecked = DayOfWeek.FRIDAY in days
        binding.chipSat.isChecked = DayOfWeek.SATURDAY in days
        binding.chipSun.isChecked = DayOfWeek.SUNDAY in days
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnSaveSchedule.isEnabled = false
            val loaded = withContext(Dispatchers.IO) {
                Triple(
                    scheduleId?.let { repository.get(it) },
                    geofenceRepository.listAll(),
                    mapRepository.listAll()
                )
            }
            if (_binding == null) return@launch
            fences = loaded.second
            existing = loaded.first
            val schedule = existing
            if (schedule != null) {
                binding.scheduleNameInput.setText(schedule.name)
                type = schedule.type
                times.clear()
                times.addAll(schedule.timesOfDayMinutes.sorted())
                if (times.isEmpty()) times.add(9 * 60)
                if (schedule.windowEndMinutes > schedule.windowStartMinutes) {
                    windowStartMinutes = schedule.windowStartMinutes
                    windowEndMinutes = schedule.windowEndMinutes
                }
                intervalMinutes = schedule.intervalMinutes.coerceAtLeast(1)
                binding.intervalInput.setText(intervalMinutes.toString())
                selectedFenceIds.clear()
                val knownFences = loaded.second.map { it.id }.toSet()
                selectedFenceIds.addAll(schedule.geofenceIds.filter { it in knownFences })
                selectWeekdays(schedule.weekdays)
                binding.scheduleEnabledSwitch.isChecked = schedule.enabled
            } else {
                if (times.isEmpty()) times.add(9 * 60)
                binding.intervalInput.setText("10")
                selectWeekdays(weekdays)
                binding.scheduleEnabledSwitch.isChecked = true
            }
            binding.typeToggle.check(
                if (type == ScheduleType.REPEATING_WINDOW) R.id.btn_type_window
                else R.id.btn_type_specific
            )
            binding.btnWindowStart.text = Schedule.formatMinute(windowStartMinutes)
            binding.btnWindowEnd.text = Schedule.formatMinute(windowEndMinutes)
            renderType()
            renderTimes()
            renderFences()
            binding.btnSaveSchedule.isEnabled = true
            loadedOnce = true
            (activity as? MainActivity)?.refreshAppBar()
        }
    }

    private fun refreshFences() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { geofenceRepository.listAll() }
            if (_binding == null) return@launch
            fences = loaded
            val known = fences.map { it.id }.toSet()
            selectedFenceIds.retainAll(known)
            renderFences()
        }
    }

    private fun renderType() {
        val specific = type == ScheduleType.SPECIFIC_TIMES
        binding.specificContainer.isVisible = specific
        binding.windowContainer.isVisible = !specific
        binding.formError.isVisible = false
    }

    private fun renderTimes() {
        binding.timesGroup.removeAllViews()
        times.sorted().forEach { minute ->
            val chip = Chip(requireContext()).apply {
                text = Schedule.formatMinute(minute)
                isCheckable = false
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    times.remove(minute)
                    renderTimes()
                }
                contentDescription = "Remove ${Schedule.formatMinute(minute)}"
            }
            binding.timesGroup.addView(chip)
        }
        binding.timesGroup.isVisible = times.isNotEmpty()
    }

    private fun renderFences() {
        binding.geofenceGroup.removeAllViews()
        fences.forEach { fence ->
            val chip = Chip(requireContext()).apply {
                text = if (fence.enabled) fence.name else "${fence.name} (off)"
                isCheckable = true
                isChecked = fence.id in selectedFenceIds
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedFenceIds.add(fence.id)
                    else selectedFenceIds.remove(fence.id)
                }
            }
            binding.geofenceGroup.addView(chip)
        }
        binding.noGeofencesNote.isVisible = fences.isEmpty()
        binding.geofenceGroup.isVisible = fences.isNotEmpty()
    }

    private fun showTimePicker(title: String, initialMinute: Int, onPicked: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(
                if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H
                else TimeFormat.CLOCK_12H
            )
            .setHour(initialMinute / 60)
            .setMinute(initialMinute % 60)
            .setTitleText(title)
            .build()
        picker.addOnPositiveButtonClickListener {
            onPicked(picker.hour * 60 + picker.minute)
        }
        picker.show(parentFragmentManager, "time_picker")
    }

    private fun createFence() {
        viewLifecycleOwner.lifecycleScope.launch {
            val maps = withContext(Dispatchers.IO) { mapRepository.listAll() }
            if (_binding == null) return@launch
            if (maps.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Download a map first")
                    .setMessage("Geofences are drawn on downloaded OpenStreetMap data.")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.download_map) { _, _ ->
                        (activity as? MainActivity)?.openDownloadMap()
                    }
                    .show()
            } else {
                (activity as? MainActivity)?.openMap(maps.first().id, enterCreation = true)
            }
        }
    }

    private fun save() {
        binding.formError.isVisible = false
        binding.scheduleNameLayout.error = null
        binding.intervalLayout.error = null
        val name = binding.scheduleNameInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.scheduleNameLayout.error = "Enter a schedule name."
            return
        }
        if (weekdays.isEmpty()) {
            formError("Select at least one day of the week.")
            return
        }
        val interval = if (type == ScheduleType.REPEATING_WINDOW) {
            binding.intervalInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
        } else {
            intervalMinutes
        }
        if (type == ScheduleType.REPEATING_WINDOW && interval <= 0) {
            binding.intervalLayout.error = "Enter a repeat interval in minutes."
            return
        }
        if (selectedFenceIds.isEmpty()) {
            formError("Select at least one geofence for this schedule.")
            return
        }

        val now = System.currentTimeMillis()
        val schedule = Schedule(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            type = type,
            timesOfDayMinutes = if (type == ScheduleType.SPECIFIC_TIMES) times.sorted() else emptyList(),
            weekdays = weekdays.toSet(),
            windowStartMinutes = windowStartMinutes,
            windowEndMinutes = windowEndMinutes,
            intervalMinutes = interval,
            geofenceIds = selectedFenceIds.toList(),
            enabled = binding.scheduleEnabledSwitch.isChecked,
            createdAt = existing?.createdAt ?: now
        )
        val validation = SchedulePlanner.validate(schedule)
        if (validation != null) {
            if (validation.startsWith("Interval")) binding.intervalLayout.error = validation
            formError(validation)
            return
        }

        binding.btnSaveSchedule.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.save(schedule) }
                val scheduler = AlarmScheduler(requireContext())
                runCatching {
                    if (schedule.enabled) {
                        scheduler.scheduleNext(schedule)
                    } else {
                        scheduler.cancel(schedule.id)
                    }
                }
                if (_binding == null) return@launch
                Toast.makeText(requireContext(), "Schedule saved", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.btnSaveSchedule.isEnabled = true
                formError("Could not save the schedule. Please try again.")
            }
        }
    }

    private fun formError(message: String) {
        binding.formError.text = message
        binding.formError.isVisible = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String =
        if (existing == null) "New schedule" else "Edit schedule"

    companion object {
        private const val ARG_SCHEDULE_ID = "schedule_id"

        fun newInstance(scheduleId: String? = null): ScheduleEditFragment =
            ScheduleEditFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCHEDULE_ID, scheduleId)
                }
            }
    }
}
