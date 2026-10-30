package com.geofenceoffline.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.geofenceoffline.R
import com.geofenceoffline.checks.AlarmScheduler
import com.geofenceoffline.checks.NotificationHelper
import com.geofenceoffline.data.MapRepository
import com.geofenceoffline.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView
import java.util.concurrent.Executors

interface Screen {
    fun screenTitle(): String

    /** Home uses the navigation drawer instead of an up arrow. */
    fun usesDrawer(): Boolean = false
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.getRoot())

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, binding.getRoot()).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        applyInsets()
        NotificationHelper.createChannels(this)

        binding.navigationView.setNavigationItemSelectedListener(::onDrawerItemSelected)
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawer: android.view.View) {
                binding.toolbar.setNavigationIcon(R.drawable.ic_close)
                binding.toolbar.setNavigationContentDescription("Close menu")
            }

            override fun onDrawerClosed(drawer: android.view.View) {
                refreshAppBar()
            }
        })

        supportFragmentManager.addOnBackStackChangedListener {
            refreshAppBar()
        }

        if (savedInstanceState == null) {
            replaceRoot(HomeFragment())
            if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_HISTORY, false)) {
                openScreen(HistoryFragment())
            }
        } else {
            refreshAppBar()
        }

        runMaintenance()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_HISTORY, false)) {
            while (supportFragmentManager.popBackStackImmediate()) {
                // Return to the root before opening history.
            }
            replaceRoot(HomeFragment())
            openScreen(HistoryFragment())
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot()) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.appBarContainer.updatePadding(top = bars.top)
            binding.fragmentContainer.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            binding.navigationView.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun onDrawerItemSelected(item: android.view.MenuItem): Boolean {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        val target: Fragment = when (item.itemId) {
            R.id.nav_maps -> MapsFragment()
            R.id.nav_geofences -> GeofencesFragment()
            R.id.nav_schedules -> ScheduleListFragment()
            R.id.nav_history -> HistoryFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> return false
        }
        while (supportFragmentManager.popBackStackImmediate()) {
            // Drawer destinations are top-level from Home.
        }
        replaceRoot(HomeFragment())
        openScreen(target)
        item.isChecked = true
        return true
    }

    fun replaceRoot(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment, fragment.javaClass.simpleName)
            .commit()
        supportFragmentManager.executePendingTransactions()
        refreshAppBar()
    }

    fun openScreen(fragment: Fragment, addToBackStack: Boolean = true) {
        val transaction = supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragment_container, fragment, fragment.javaClass.simpleName)
        if (addToBackStack) transaction.addToBackStack(fragment.javaClass.simpleName)
        transaction.commit()
        supportFragmentManager.executePendingTransactions()
        refreshAppBar()
    }

    fun refreshAppBar() {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val screen = fragment as? Screen
        binding.toolbar.title = screen?.screenTitle() ?: getString(R.string.app_name)
        val usesDrawer = screen?.usesDrawer() == true
        if (usesDrawer) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_menu)
            binding.toolbar.setNavigationContentDescription("Open menu")
            binding.toolbar.setNavigationOnClickListener {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        } else {
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.setNavigationContentDescription("Back")
            binding.toolbar.setNavigationOnClickListener {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        binding.toolbar.setTitleTextColor(Color.WHITE)
    }

    fun openMaps() {
        openScreen(MapsFragment())
    }

    fun openGeofences() {
        openScreen(GeofencesFragment())
    }

    fun openSchedules() {
        openScreen(ScheduleListFragment())
    }

    fun openHistory() {
        while (supportFragmentManager.popBackStackImmediate()) {
            // Keep a simple stack.
        }
        replaceRoot(HomeFragment())
        openScreen(HistoryFragment())
    }

    fun openSettings() {
        openScreen(SettingsFragment())
    }

    fun openDownloadMap() {
        openScreen(DownloadMapFragment())
    }

    fun openMap(mapId: String, selectedGeofenceId: String? = null, enterCreation: Boolean = false) {
        openScreen(MapFragment.newInstance(mapId, selectedGeofenceId, enterCreation))
    }

    fun drawerLayout(): DrawerLayout = binding.drawerLayout

    private fun runMaintenance() {
        MAINTENANCE.execute {
            runCatching { MapRepository(this).cleanupTemporaryDownloads() }
            runCatching { AlarmScheduler(this).rescheduleAll() }
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAppBar()
    }

    companion object {
        private val MAINTENANCE = Executors.newSingleThreadExecutor()
    }
}
