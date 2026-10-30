package com.geofenceoffline.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.geofenceoffline.R
import com.geofenceoffline.data.CheckHistoryRepository
import com.geofenceoffline.data.CheckReason
import com.geofenceoffline.data.CheckResult
import com.geofenceoffline.data.CheckStatus
import com.geofenceoffline.databinding.FragmentHistoryBinding
import com.geofenceoffline.databinding.ItemHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryFragment : Fragment(), Screen {
    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy { CheckHistoryRepository(requireContext()) }
    private var filter: CheckStatus? = null
    private var results: List<CheckResult> = emptyList()
    private var totalCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when {
                checkedIds.contains(R.id.filter_inside) -> CheckStatus.INSIDE
                checkedIds.contains(R.id.filter_outside) -> CheckStatus.OUTSIDE
                checkedIds.contains(R.id.filter_unknown) -> CheckStatus.UNKNOWN
                else -> null
            }
            reload()
        }
        binding.btnClearHistory.setOnClickListener { confirmClearAll() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                repository.list(filter) to repository.count()
            }
            if (_binding == null) return@launch
            results = loaded.first
            totalCount = loaded.second
            render()
        }
    }

    private fun render() {
        binding.historyContainer.removeAllViews()
        binding.historyEmpty.isVisible = results.isEmpty()
        binding.btnClearHistory.isEnabled = totalCount > 0
        binding.historyCount.text = when {
            totalCount == 0 -> "No stored checks"
            filter == null -> Formats.countLabel(totalCount, "check")
            else -> "${results.size} ${Formats.statusLabel(filter!!).lowercase()} of $totalCount"
        }
        if (results.isEmpty()) {
            binding.historyEmptyTitle.text = when (filter) {
                CheckStatus.INSIDE -> "No INSIDE results"
                CheckStatus.OUTSIDE -> "No OUTSIDE results"
                CheckStatus.UNKNOWN -> "No UNKNOWN checks"
                null -> "No checks yet"
            }
            binding.historyEmptyBody.text = if (filter == null) {
                "Results appear here after a schedule runs. Failed checks are shown as UNKNOWN, " +
                    "never as inside or outside."
            } else {
                "No stored result matches this filter. Try All to see the complete history."
            }
        }

        results.forEach { result ->
            val item = ItemHistoryBinding.inflate(layoutInflater, binding.historyContainer, false)
            item.historyTime.text = Formats.dateTime(result.scheduledAt)
            item.historySchedule.text = buildString {
                append("Schedule · ")
                append(result.scheduleName)
                if (result.executedAt - result.scheduledAt > 60_000L) {
                    append(" · ran ")
                    append(Formats.time(result.executedAt))
                }
            }
            item.historyFence.text = result.geofenceName
            val (chip, dot, color) = when (result.status) {
                CheckStatus.INSIDE -> Triple(
                    R.drawable.bg_chip_green, R.drawable.bg_dot_green, R.color.green
                )
                CheckStatus.OUTSIDE -> Triple(
                    R.drawable.bg_chip_blue, R.drawable.bg_dot_blue, R.color.blue
                )
                CheckStatus.UNKNOWN -> Triple(
                    R.drawable.bg_chip_amber, R.drawable.bg_dot_amber, R.color.amber
                )
            }
            item.historyStatus.setBackgroundResource(chip)
            item.historyStatus.setTextColor(ContextCompat.getColor(requireContext(), color))
            item.historyStatus.text = Formats.statusLabel(result.status)
            item.historyDot.setBackgroundResource(dot)
            if (result.status == CheckStatus.UNKNOWN) {
                item.historyReason.isVisible = true
                item.historyReason.text = result.reason.label()
                item.historyReason.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.amber)
                )
            } else {
                item.historyReason.isVisible = false
            }
            item.root.setOnClickListener { showDetail(result) }
            binding.historyContainer.addView(item.root)
        }
    }

    private fun showDetail(result: CheckResult) {
        val message = buildString {
            append("Geofence: ")
            append(result.geofenceName)
            append("\nSchedule: ")
            append(result.scheduleName)
            append("\nScheduled: ")
            append(Formats.dateTime(result.scheduledAt))
            append("\nExecuted: ")
            append(Formats.dateTime(result.executedAt))
            if (result.status == CheckStatus.UNKNOWN) {
                append("\nStatus: UNKNOWN · ")
                append(result.reason.label())
                append("\nNo inside/outside result was recorded.")
            }
            append("\n\nOnly this result and its timestamps are stored. The device location " +
                "used for the calculation was never saved.")
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(Formats.statusLabel(result.status))
            .setMessage(message)
            .setNegativeButton("Delete entry") { _, _ ->
                confirmDeleteEntry(result)
            }
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
    }

    private fun confirmDeleteEntry(result: CheckResult) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete this result?")
            .setMessage("This removes only this timestamped check result from the device.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.delete(result.id) }
                    Toast.makeText(requireContext(), "History entry deleted", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear all check history?")
            .setMessage(
                "Every stored INSIDE, OUTSIDE and UNKNOWN result is deleted from this device. " +
                    "Geofences and schedules are kept. This cannot be undone."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Clear history") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.deleteAll() }
                    Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun screenTitle(): String = getString(R.string.history)
}
