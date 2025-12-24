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
import com.ichi2.anki.showThemedToast
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.utils.openUrl
import com.ichi2.compat.CompatHelper
import com.ichi2.utils.show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.sink
import org.vosk.Model
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

@Suppress("ktlint:standard:property-naming")
class AdvancedSettingsFragment : SettingsFragment() {
    private val vosk_manager by lazy { VoskModelManager(requireContext()) }

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
        setupVoskModelPreference()
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

    private fun setupVoskModelPreference() {
        val pref = findPreference<Preference>(getString(R.string.pref_vosk_model_key)) ?: return
        val model_key = getString(R.string.pref_vosk_model_choice_key)

        fun refreshSummary(progress: Int? = null) {
            val selected = requireContext().sharedPrefs().getString(model_key, "cn_small") ?: "cn_small"
            pref.summary =
                when (vosk_manager.status(selected)) {
                    VoskModelManager.Status.NOT_DOWNLOADED -> getString(R.string.vosk_pref_desc_not_loaded)
                    VoskModelManager.Status.DOWNLOADING ->
                        getString(R.string.vosk_pref_desc_downloading, progress ?: vosk_manager.currentProgress(selected))
                    VoskModelManager.Status.READY -> getString(R.string.vosk_pref_desc_loaded)
                }
        }
        refreshSummary()
        pref.setOnPreferenceClickListener {
            val selected = requireContext().sharedPrefs().getString(model_key, "cn_small") ?: "cn_small"
            when (vosk_manager.status(selected)) {
                VoskModelManager.Status.READY -> {
                    showThemedToast(requireContext(), getString(R.string.vosk_pref_desc_loaded), true)
                }
                VoskModelManager.Status.DOWNLOADING -> {
                    showThemedToast(
                        requireContext(),
                        getString(R.string.vosk_pref_desc_downloading, vosk_manager.currentProgress(selected)),
                        true,
                    )
                }
                VoskModelManager.Status.NOT_DOWNLOADED -> {
                    launchCatchingTask {
                        vosk_manager.downloadModel(
                            selected,
                            onProgress = { pct -> refreshSummary(pct) },
                            onError = { msg ->
                                refreshSummary()
                                showThemedToast(requireContext(), getString(R.string.vosk_pref_error_download, msg), false)
                            },
                            onComplete = {
                                refreshSummary()
                                showThemedToast(requireContext(), getString(R.string.vosk_pref_desc_loaded), true)
                            },
                        )
                    }
                    refreshSummary()
                }
            }
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

@Suppress("ktlint:standard:property-naming", "ktlint:standard:function-naming")
class VoskModelManager(
    private val context: Context,
) {
    enum class Status {
        NOT_DOWNLOADED,
        DOWNLOADING,
        READY,
    }

    private data class ModelInfo(
        val id: String,
        val url: String,
        val folder: String,
    )

    private val models =
        mapOf(
            "cn_small" to ModelInfo("cn_small", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", "model_cn_small"),
            "en_small" to ModelInfo("en_small", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "model_en_small"),
        )

    private val progress_map = mutableMapOf<String, Int>()
    private val downloading = mutableSetOf<String>()

    fun status(model_id: String): Status {
        val info = models[model_id] ?: return Status.NOT_DOWNLOADED
        if (downloading.contains(info.id)) return Status.DOWNLOADING
        val marker = marker_file(info)
        return if (marker.exists() && info_dir(info).exists()) Status.READY else Status.NOT_DOWNLOADED
    }

    fun currentProgress(model_id: String): Int = progress_map[model_id] ?: 0

    fun modelPath(model_id: String): String? {
        val info = models[model_id] ?: return null
        val dir = info_dir(info)
        return if (status(model_id) == Status.READY) dir.absolutePath else null
    }

    suspend fun downloadModel(
        model_id: String,
        onProgress: (Int) -> Unit = {},
        onError: (String) -> Unit = {},
        onComplete: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val info = models[model_id] ?: return@withContext
        if (status(model_id) == Status.DOWNLOADING) return@withContext
        downloading.add(info.id)
        progress_map[info.id] = 0
        val result =
            kotlin.runCatching {
                val client = OkHttpClient()
                val request = Request.Builder().url(info.url).build()
                val tmp_zip = File(context.cacheDir, "vosk_${info.id}.zip")
                val local_zip = local_zip_file(info)
                val zip_to_use =
                    local_zip?.takeIf { it.exists() }?.also {
                        Timber.i("using local vosk zip for ${info.id}: ${it.absolutePath}")
                    }
                        ?: tmp_zip.also {
                            client.newCall(request).execute().use { resp ->
                                if (!resp.isSuccessful) error("http ${resp.code}")
                                val body = resp.body ?: error("empty body")
                                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                                body.source().use { source ->
                                    tmp_zip.sink().buffer().use { sink ->
                                        var read: Long
                                        var bytes_copied = 0L
                                        val buffer = Buffer()
                                        while (source.read(buffer, 8_192).also { read = it } != -1L) {
                                            sink.write(buffer, read)
                                            bytes_copied += read
                                            if (total > 0) {
                                                val pct = ((bytes_copied * 100) / total).toInt()
                                                progress_map[info.id] = pct
                                                onProgress(pct)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                unzip_to_dir(info, zip_to_use)
                onComplete()
            }.onFailure { e ->
                Timber.w(e, "vosk model download failed")
                onError(e.message ?: "unknown")
            }
        downloading.remove(info.id)
        progress_map.remove(info.id)
        result.exceptionOrNull()?.let { return@withContext }
    }

    private fun unzip_to_dir(info: ModelInfo, zipFile: File) {
        val target_dir = info_dir(info)
        if (target_dir.exists()) target_dir.deleteRecursively()
        target_dir.mkdirs()
        zipFile.parentFile?.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val relative = entry.name.substringAfter("/", entry.name)
                if (relative.isEmpty()) {
                    entry = zis.nextEntry
                    continue
                }
                val out_file = File(target_dir, relative)
                if (entry.isDirectory) {
                    out_file.mkdirs()
                } else {
                    out_file.parentFile?.mkdirs()
                    out_file.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        marker_file(info).writeText("ready")
    }

    fun loadModelIfReady(model_id: String): Model? {
        val info = models[model_id] ?: return null
        if (status(model_id) != Status.READY) return null
        return runCatching { Model(info_dir(info).absolutePath) }.onFailure { e ->
            Timber.w(e, "failed to load vosk model")
        }.getOrNull()
    }

    private fun info_dir(info: ModelInfo): File = File(context.filesDir, "vosk/${info.folder}")

    private fun marker_file(info: ModelInfo): File = File(info_dir(info), ".model_ready")

    private fun local_zip_file(info: ModelInfo): File? =
        context.getExternalFilesDir(null)?.let { base ->
            File(base, "vosk/${info.id}.zip")
        }
}
