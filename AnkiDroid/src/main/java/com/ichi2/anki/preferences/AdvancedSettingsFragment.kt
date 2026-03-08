@file:Suppress(
    "ktlint:standard:property-naming",
    "ktlint:standard:function-naming",
    "ktlint:standard:indent",
    "ktlint:standard:chain-method-continuation",
    "ktlint:standard:function-signature",
    "ktlint:standard:trailing-comma-on-declaration-site",
)
/*
 *  Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.preferences

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.ichi2.anki.CollectionHelper
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.MetaDB
import com.ichi2.anki.R
import com.ichi2.anki.exception.StorageAccessException
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.preferences.sharedPrefs
import com.ichi2.anki.provider.CardContentProvider
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.speech.WhisperModelManager
import com.ichi2.anki.utils.openUrl
import com.ichi2.compat.CompatHelper
import com.ichi2.utils.show
import timber.log.Timber
import java.io.File

@Suppress("ktlint:standard:property-naming")
class AdvancedSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_advanced
    override val analyticsScreenNameConstant: String
        get() = "prefs.advanced"

    override fun initSubscreen() {
        removeUnnecessaryAdvancedPrefs()

        // Check that input is valid before committing change in the collection path
        requirePreference<EditTextPreference>(CollectionHelper.PREF_COLLECTION_PATH).apply {
            setOnPreferenceChangeListener { _, newValue: Any? ->
                val newPath = newValue as String
                try {
                    CollectionHelper.initializeAnkiDroidDirectory(File(newPath))
                    launchCatchingTask {
                        CollectionManager.discardBackend()
                        val deckPicker = Intent(requireContext(), DeckPicker::class.java)
                        deckPicker.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(deckPicker)
                    }
                    true
                } catch (e: StorageAccessException) {
                    // TODO: Request MANAGE_EXTERNAL_STORAGE
                    Timber.e(e, "Could not initialize directory: %s", newPath)
                    AlertDialog.Builder(requireContext()).show {
                        setTitle(R.string.dialog_collection_path_not_dir)
                        setPositiveButton(R.string.dialog_ok) { _, _ -> }
                        setNegativeButton(R.string.reset_custom_buttons) { _, _ ->
                            text = CollectionHelper.getDefaultAnkiDroidDirectory(requireContext()).absolutePath
                        }
                    }
                    false
                }
            }
        }

        val ttsPref = requirePreference<SwitchPreferenceCompat>(R.string.tts_key)
        ttsPref.setOnPreferenceChangeListener { _, isChecked ->
            if (!(isChecked as Boolean)) return@setOnPreferenceChangeListener true
            AlertDialog.Builder(requireContext()).show {
                setIcon(R.drawable.ic_warning)
                setMessage(R.string.readtext_deprecation_warn)
                setNegativeButton(R.string.dialog_cancel) { _, _ -> ttsPref.isChecked = false }
                setNeutralButton(R.string.scoped_storage_learn_more) { _, _ ->
                    ttsPref.isChecked = false
                    requireContext().openUrl(R.string.link_tts)
                }
                setPositiveButton(R.string.dialog_ok) { _, _ -> }
                setOnCancelListener { ttsPref.isChecked = false }
            }
            return@setOnPreferenceChangeListener true
        }

        // Configure "Reset languages" preference
        requirePreference<Preference>(R.string.pref_reset_languages_key).setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext()).show {
                setTitle(R.string.reset_languages)
                setIcon(R.drawable.ic_warning)
                setMessage(R.string.reset_languages_question)
                setPositiveButton(R.string.dialog_ok) { _, _ ->
                    if (MetaDB.resetLanguages(requireContext())) {
                        showSnackbar(R.string.reset_confirmation)
                    }
                }
                setNegativeButton(R.string.dialog_cancel) { _, _ -> }
            }
            false
        }

        /*
         * Plugins section
         */

        // Third party apps
        requirePreference<Preference>(R.string.thirdparty_apps_key).setOnPreferenceClickListener {
            requireContext().openUrl(R.string.link_third_party_api_apps)
            false
        }

        // Enable API
        requirePreference<SwitchPreferenceCompat>(R.string.enable_api_key).setOnPreferenceChangeListener { newValue ->
            val providerName = ComponentName(requireContext(), CardContentProvider::class.java.name)
            val state =
                if (newValue) {
                    Timber.i("AnkiDroid ContentProvider enabled by user")
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    Timber.i("AnkiDroid ContentProvider disabled by user")
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            requireActivity().packageManager.setComponentEnabledSetting(providerName, state, PackageManager.DONT_KILL_APP)
        }

        setupNewStudyScreenSettings()
        setupWhisperModelPreference()
    }

    private fun removeUnnecessaryAdvancedPrefs() {
        /* These preferences should be searchable or not based
         * on this same condition at [HeaderFragment.configureSearchBar] */
        // Disable the double scroll preference if no scrolling keys
        if (!CompatHelper.hasScrollKeys()) {
            val doubleScrolling = findPreference<SwitchPreferenceCompat>("double_scrolling")
            if (doubleScrolling != null) {
                preferenceScreen.removePreference(doubleScrolling)
            }
        }
    }

    private fun setupNewStudyScreenSettings() {
        if (!Prefs.isNewStudyScreenEnabled) return
        for (key in legacyStudyScreenSettings) {
            val keyString = getString(key)
            findPreference<Preference>(keyString)?.isVisible = false
        }
    }

    private fun setupWhisperModelPreference() {
        val status_pref = findPreference<Preference>(getString(R.string.pref_whisper_model_status_key)) ?: return
        val whisper_manager = WhisperModelManager(requireContext())

        fun refresh() {
            whisper_manager.update_summary { status_pref.summary = it }
        }

        refresh()
        status_pref.setOnPreferenceClickListener {
            refresh()
            when (whisper_manager.status()) {
                WhisperModelManager.Status.READY,
                WhisperModelManager.Status.READY_LOCAL,
                -> {
                    showSnackbar(status_pref.summary.toString())
                }
                WhisperModelManager.Status.DOWNLOADING -> {
                    showSnackbar(getString(R.string.whisper_model_status_downloading, whisper_manager.progress()))
                }
                WhisperModelManager.Status.NOT_READY -> {
                    launchCatchingTask {
                        whisper_manager.download_model(
                            on_progress = { pct ->
                                status_pref.summary = getString(R.string.whisper_model_status_downloading, pct)
                            },
                            on_error = { msg ->
                                status_pref.summary = getString(R.string.whisper_model_status_not_ready)
                                showSnackbar(getString(R.string.whisper_model_download_failed, msg))
                            },
                            on_complete = {
                                refresh()
                                showSnackbar(status_pref.summary.toString())
                            },
                        )
                    }
                }
            }
            true
        }

        // When URL/path changes, refresh the status line
        findPreference<EditTextPreference>(getString(R.string.pref_whisper_model_url_key))?.setOnPreferenceChangeListener { _, _ ->
            refresh()
            true
        }
        findPreference<EditTextPreference>(getString(R.string.pref_whisper_model_local_path_key))?.setOnPreferenceChangeListener { _, _ ->
            refresh()
            true
        }
    }

    companion object {
        val legacyStudyScreenSettings =
            listOf(
                R.string.pref_reset_languages_key,
                R.string.double_scrolling_gap_key,
                R.string.tts_key,
            )
    }
}
