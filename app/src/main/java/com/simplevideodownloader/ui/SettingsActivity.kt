package com.simplevideodownloader.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplevideodownloader.BuildConfig
import com.simplevideodownloader.R
import com.simplevideodownloader.adblock.AdBlocker
import com.simplevideodownloader.adblock.FilterLists
import com.simplevideodownloader.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import java.util.Date

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<MultiSelectListPreference>(AdBlocker.PREF_LISTS)?.apply {
                entries = FilterLists.all.map { it.name }.toTypedArray()
                entryValues = FilterLists.all.map { it.id }.toTypedArray()
                if (values.isNullOrEmpty() && !preferenceManager.sharedPreferences!!.contains(key)) values = FilterLists.defaultIds
                summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { pref ->
                    FilterLists.all.filter { it.id in pref.values }.joinToString(", ") { it.name }.ifEmpty { "-" }
                }
                setOnPreferenceChangeListener { _, _ ->
                    // Al cambiar de listas descargamos las nuevas y reconstruimos el motor.
                    view?.post { updateLists() }
                    true
                }
            }

            findPreference<Preference>("adblock_update")?.setOnPreferenceClickListener {
                updateLists()
                true
            }

            findPreference<Preference>("adblock_allowlist_view")?.setOnPreferenceClickListener {
                val sites = AdBlocker.allowlist().sorted()
                if (sites.isNotEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.pref_allowlist_clear_title)
                        .setMessage(sites.joinToString("\n"))
                        .setPositiveButton(R.string.pref_allowlist_clear) { _, _ ->
                            AdBlocker.clearAllowlist()
                            refreshAllowlistSummary()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                true
            }

            findPreference<Preference>("about")?.summary = getString(R.string.pref_about_summary, BuildConfig.VERSION_NAME)
            refreshAllowlistSummary()

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    AdBlocker.status.collect { showStatus(it) }
                }
            }
        }

        private fun showStatus(status: AdBlocker.Status) {
            val last = if (status.lastUpdate > 0) {
                DateFormat.getMediumDateFormat(requireContext()).format(Date(status.lastUpdate))
            } else {
                getString(R.string.pref_adblock_never)
            }
            findPreference<Preference>("adblock_update")?.summary =
                getString(R.string.pref_adblock_update_summary, status.networkRules, status.cosmeticRules, last)
        }

        private fun refreshAllowlistSummary() {
            val sites = AdBlocker.allowlist()
            findPreference<Preference>("adblock_allowlist_view")?.summary =
                if (sites.isEmpty()) getString(R.string.pref_allowlist_summary_empty) else sites.sorted().joinToString(", ")
        }

        private fun updateLists() {
            val pref = findPreference<Preference>("adblock_update") ?: return
            pref.isEnabled = false
            pref.summary = getString(R.string.pref_adblock_updating)
            val appContext = requireContext().applicationContext
            lifecycleScope.launch {
                val updated = AdBlocker.updateLists()
                pref.isEnabled = true
                if (isAdded) showStatus(AdBlocker.status.value)
                Toast.makeText(
                    appContext,
                    if (updated > 0) appContext.getString(R.string.pref_adblock_updated, updated)
                    else appContext.getString(R.string.pref_adblock_update_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
