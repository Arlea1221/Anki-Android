/* **************************************************************************************
 * Copyright (c) 2026                                                                     *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki.speech

import android.content.Context
import com.ichi2.anki.R
import com.ichi2.anki.preferences.sharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.sink
import timber.log.Timber
import java.io.File

@Suppress("ktlint:standard:property-naming", "ktlint:standard:function-naming")
class WhisperModelManager(
    private val context: Context,
) {
    enum class Status {
        NOT_READY,
        DOWNLOADING,
        READY,
        READY_LOCAL,
    }

    private val client = OkHttpClient()
    private var downloading = false
    private var progress_pct = 0

    fun status(): Status {
        val local = local_model_file()
        if (local != null) return Status.READY_LOCAL
        val downloaded = downloaded_model_file()
        if (downloaded != null) return Status.READY
        return if (downloading) Status.DOWNLOADING else Status.NOT_READY
    }

    fun progress(): Int = progress_pct

    fun active_model_path(): String? = local_model_file()?.absolutePath ?: downloaded_model_file()?.absolutePath

    fun active_model_display(): String? = active_model_path()?.let { File(it).name }

    fun update_summary(summary: (String) -> Unit) {
        val s =
            when (val st = status()) {
                Status.NOT_READY -> context.getString(R.string.whisper_model_status_not_ready)
                Status.DOWNLOADING -> context.getString(R.string.whisper_model_status_downloading, progress_pct)
                Status.READY_LOCAL ->
                    context.getString(
                        R.string.whisper_model_status_using_local,
                        active_model_display().orEmpty(),
                    )
                Status.READY ->
                    context.getString(
                        R.string.whisper_model_status_ready,
                        active_model_display().orEmpty(),
                    )
            }
        summary(s)
    }

    suspend fun download_model(
        on_progress: (Int) -> Unit = {},
        on_error: (String) -> Unit = {},
        on_complete: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        if (downloading) return@withContext
        if (local_model_file() != null) {
            on_complete()
            return@withContext
        }
        val url = model_url()
        if (url.isBlank()) {
            on_error("empty url")
            return@withContext
        }
        downloading = true
        progress_pct = 0
        runCatching {
            val target = model_download_target(url)
            val tmp = File(target.parentFile, "${target.name}.download")
            tmp.parentFile?.mkdirs()

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("http ${resp.code}")
                val body = resp.body ?: error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.source().use { source ->
                    tmp.sink().buffer().use { sink ->
                        val buffer = Buffer()
                        var read: Long
                        var bytes_copied = 0L
                        while (source.read(buffer, 8_192).also { read = it } != -1L) {
                            sink.write(buffer, read)
                            bytes_copied += read
                            if (total > 0) {
                                val pct = ((bytes_copied * 100) / total).toInt()
                                if (pct != progress_pct) {
                                    progress_pct = pct
                                    on_progress(pct)
                                }
                            }
                        }
                    }
                }
            }
            if (target.exists()) {
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            progress_pct = 100
            on_progress(100)
            on_complete()
        }.onFailure { e ->
            Timber.w(e, "whisper model download failed")
            on_error(e.message ?: "unknown")
        }
        downloading = false
    }

    private fun model_url(): String =
        context.sharedPrefs().getString(
            context.getString(R.string.pref_whisper_model_url_key),
            "",
        ) ?: ""

    private fun local_model_path(): String =
        context.sharedPrefs().getString(
            context.getString(R.string.pref_whisper_model_local_path_key),
            "",
        ) ?: ""

    private fun local_model_file(): File? {
        val path = local_model_path().trim()
        if (path.isBlank()) return null
        val file = File(path)
        return file.takeIf { it.exists() && it.isFile && it.length() > 0 }
    }

    private fun downloaded_model_file(): File? {
        val url = model_url().trim()
        if (url.isBlank()) return null
        val file = model_download_target(url)
        return file.takeIf { it.exists() && it.isFile && it.length() > 0 }
    }

    private fun model_download_target(url: String): File {
        val filename = url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "ggml-model.bin"
        return File(File(context.filesDir, "whisper/models"), filename)
    }
}
