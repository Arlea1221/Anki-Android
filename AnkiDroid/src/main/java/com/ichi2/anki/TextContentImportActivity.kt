@file:Suppress(
    "ktlint:standard:property-naming",
    "ktlint:standard:function-naming",
    "ktlint:standard:chain-method-continuation",
)
/* --------------------------------------------------------------------------------------
 * Copyright (c) 2015 Timothy Rae <perceptualchaos2@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 * ------------------------------------------------------------------------------------ */

package com.ichi2.anki

import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.core.content.FileProvider
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.snackbar.showSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("ktlint:standard:property-naming", "ktlint:standard:function-naming")
class TextContentImportActivity : AnkiActivity() {
    private lateinit var text_input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_content_import)

        setup_text_input()
    }

    private fun setup_text_input() {
        text_input = findViewById(R.id.text_input)
        text_input.hint = getString(R.string.text_content_import_hint)

        // 自动从剪贴板获取内容
        val clipboard_manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip_data = clipboard_manager.primaryClip
        if (clip_data != null && clip_data.itemCount > 0) {
            val clip_text = clip_data.getItemAt(0).text
            if (!clip_text.isNullOrBlank()) {
                text_input.setText(clip_text.toString())
                text_input.setSelection(text_input.text.length) // 将光标移到末尾
                showSnackbar(getString(R.string.clipboard_auto_filled))
            }
        }

        val save_button = findViewById<Button>(R.id.save_button)
        save_button.setOnClickListener {
            import_text_content()
        }
        val newline_button = findViewById<Button>(R.id.newline_button)
        newline_button.setOnClickListener {
            insert_newline()
        }
    }

    private fun insert_newline() {
        val start = text_input.selectionStart.coerceAtLeast(0)
        val end = text_input.selectionEnd.coerceAtLeast(0)
        val replace_start = minOf(start, end)
        val replace_end = maxOf(start, end)
        val newline = "\n"
        text_input.text.replace(replace_start, replace_end, newline)
        text_input.setSelection(replace_start + newline.length)
    }

    private fun import_text_content() {
        val content = text_input.text.toString().trim()
        if (content.isEmpty()) {
            showSnackbar(getString(R.string.please_enter_text_content))
            return
        }
        val context = this
        launchCatchingTask {
            intent.getLongExtra(EXTRA_TARGET_DECK_ID, 0L)
                .takeIf { it != 0L }
                ?.let { targetDeckId -> withCol { decks.select(targetDeckId) } }
            val temp_file = create_temp_csv_file(content)
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "$packageName.apkgfileprovider",
                    temp_file,
                )
            val intent = Intent().setData(uri)

            onSelectedCsvForImport(intent)
            finish()
        }
    }

    private suspend fun create_temp_csv_file(content: String): File =
        withContext(Dispatchers.IO) {
            val temp_file = File.createTempFile("import_text", ".csv", cacheDir)
            temp_file.writeText(content)
            temp_file
        }

    companion object {
        const val EXTRA_TARGET_DECK_ID = "extra_target_deck_id"
    }
}
