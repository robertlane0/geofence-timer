package com.geofenceoffline.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.checks.AlarmScheduler
import com.geofenceoffline.checks.CheckService
import com.geofenceoffline.data.Schedule
import com.geofenceoffline.data.ScheduleRepository
import com.geofenceoffline.databinding.FragmentSchedulesBinding
import com.geofenceoffline.databinding.ItemScheduleBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScheduleListFragment : Fragment(), Screen {
    private var _binding: FragmentSchedulesBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy { ScheduleRepository(requireContext()) }
    private var schedules: List<Schedule> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSchedulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fabNewSchedule.setOnClickListener { openEditor(null) }
        binding.emptyNewSchedule.setOnClickListener { openEditor(null) }
        binding.scheduleWarningButton.setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.listAll() }
            if (_binding == null) return@launch
            schedules = loaded
            render()
        }
    }

    private fun render() {
        val missing = PermissionTools.recommendedMissing(requireContext())
        binding.scheduleWarning.isVisible = missing.isNotEmpty()
        if (missing.isNotEmpty()) {
            binding.scheduleWarningText.text =
                "Missing ${missing.joinToString(", ")}. Scheduled checks may be delayed or " +
                    "recorded as UNKNOWN."
        }

        binding.schedulesContainer.removeAllViews()
        binding.schedulesEmpty.isVisible = schedules.isEmpty()
        val scheduler = AlarmScheduler(requireContext())
        schedules.forEach { schedule ->
            val item = ItemScheduleBinding.inflate(layoutInflater, binding.schedulesContainer, false)
            item.scheduleName.text = schedule.name
            item.scheduleSummary.text = schedule.summary()
            item.scheduleNext.text = when {
                !schedule.enabled -> "Disabled"
                else -> "Next: ${scheduler.nextAlarmDescription(schedule)}"
            }
            item.root.alpha = if (schedule.enabled) 1f else 0.66f
            item.scheduleSwitch.setOnCheckedChangeListener(null)
            item.scheduleSwitch.isChecked = schedule.enabled
            item.scheduleSwitch.setOnCheckedChangeListener { _, checked ->
                setEnabled(schedule, checked)
            }
            item.root.setOnClickListener { openEditor(schedule.id) }
            item.scheduleMore.setOnClickListener { showMenu(it, schedule) }
            binding.schedulesContainer.addView(item.root)
        }
    }

    private fun setEnabled(schedule: Schedule, enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.setEnabled(schedule.id, enabled)
                val scheduler = AlarmScheduler(requireContext())
                runCatching {
                    if (enabled) {
                        scheduler.scheduleNext(schedule.copy(enabled = true))
                    } else {
                        scheduler.cancel(schedule.id)
                    }
                }
            }
            reload()
        }
    }

    private fun showMenu(anchor: View, schedule: Schedule) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_EDIT, 0, "Edit schedule")
        popup.menu.add(0, MENU_CHECK_NOW, 1, "Check now")
        popup.menu.add(0, MENU_DELETE, 2, "Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> {
                    openEditor(schedule.id)
                    true
                }
                MENU_CHECK_NOW -> {
                    runNow(schedule)
                    true
                }
                MENU_DELETE -> {
                    confirmDelete(schedule)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun runNow(schedule: Schedule) {
        if (!schedule.enabled) {
            Toast.makeText(
                requireContext(),
                "This schedule is disabled. Enable it before running a check.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (schedule.geofenceIds.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Add at least one geofence to this schedule first.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        CheckService.startNow(requireContext(), schedule.id, System.currentTimeMillis())
        Toast.makeText(
            requireContext(),
            "One private location check started. The result will appear in History.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun confirmDelete(schedule: Schedule) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete \u201c${schedule.name}\u201d?")
            .setMessage(
                "The weekly schedule and its pending alarm are removed. Existing check history " +
                    "is kept."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AlarmScheduler(requireContext()).cancel(schedule.id)
                        repository.delete(schedule.id)
                    }
                    Toast.makeText(requireContext(), "Schedule deleted", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .show()
    }

    private fun openEditor(scheduleId: String?) {
        (activity as? MainActivity)?.openScreen(ScheduleEditFragment.newInstance(scheduleId))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String = getString(R.string.schedules)

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_CHECK_NOW = 2
        private const val MENU_DELETE = 3
    }
}
