package com.jcat.ringreminder

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import com.jcat.ringreminder.databinding.ActivityAppSuppressBinding

data class AppEntry(val label: String, val packageName: String, val icon: Drawable?)

class AppSuppressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSuppressBinding
    private lateinit var prefs: PrefsHelper
    private val allApps = mutableListOf<AppEntry>()
    private val filteredApps = mutableListOf<AppEntry>()
    private lateinit var adapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSuppressBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PrefsHelper(this)
        loadApps()
        setupList()
        setupSearch()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadApps() {
        val pm = packageManager
        // Only include apps that appear in the launcher (user-visible apps)
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.GET_META_DATA
        ).map { it.activityInfo.packageName }.toSet()

        launchable
            .filter { it != packageName }
            .mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    AppEntry(
                        label = pm.getApplicationLabel(info).toString(),
                        packageName = pkg,
                        icon = pm.getApplicationIcon(info)
                    )
                } catch (_: Exception) { null }
            }
            .sortedBy { it.label.lowercase() }
            .forEach { allApps.add(it) }

        filteredApps.addAll(allApps)
    }

    private fun setupList() {
        adapter = AppListAdapter()
        binding.listApps.adapter = adapter
        binding.listApps.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        syncCheckedState()

        binding.listApps.setOnItemClickListener { _, _, position, _ ->
            val app = filteredApps[position]
            val current = prefs.suppressedApps.toMutableSet()
            if (binding.listApps.isItemChecked(position)) {
                current.add(app.packageName)
            } else {
                current.remove(app.packageName)
            }
            prefs.suppressedApps = current
        }
    }

    private fun syncCheckedState() {
        val suppressed = prefs.suppressedApps
        filteredApps.forEachIndexed { index, app ->
            binding.listApps.setItemChecked(index, suppressed.contains(app.packageName))
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText.orEmpty())
                return true
            }
        })
    }

    private fun filterApps(query: String) {
        filteredApps.clear()
        val lq = query.lowercase()
        filteredApps.addAll(
            if (lq.isEmpty()) allApps
            else allApps.filter { it.label.lowercase().contains(lq) }
        )
        adapter.notifyDataSetChanged()
        syncCheckedState()
    }

    inner class AppListAdapter : ArrayAdapter<AppEntry>(
        this@AppSuppressActivity,
        R.layout.item_app_suppress,
        R.id.app_item_text,
        filteredApps
    ) {
        private val iconSize = (36 * resources.displayMetrics.density).toInt()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as CheckedTextView
            val app = filteredApps[position]
            view.text = app.label
            val icon = app.icon
            if (icon != null) {
                icon.setBounds(0, 0, iconSize, iconSize)
                view.setCompoundDrawables(icon, null, null, null)
            } else {
                view.setCompoundDrawables(null, null, null, null)
            }
            return view
        }
    }
}
