/****************************************************************************************
 * Copyright (c) 2015 Timothy Rae <perceptualchaos2@gmail.com>                          *
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

package com.ichi2.anki

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.core.content.FileProvider
import com.ichi2.anki.snackbar.showSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TextContentImportActivity : AnkiActivity() {
    private lateinit var textInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_content_import)

        setupTextInput()
    }

    private fun setupTextInput() {
        textInput = findViewById(R.id.text_input)
        textInput.hint = getString(R.string.text_content_import_hint)

        // 自动从剪贴板获取内容
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboardManager.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val clipText = clipData.getItemAt(0).text
            if (!clipText.isNullOrBlank()) {
                textInput.setText(clipText.toString())
                textInput.setSelection(textInput.text.length) // 将光标移到末尾
                showSnackbar(getString(R.string.clipboard_auto_filled))
            }
        }

        val saveButton = findViewById<Button>(R.id.save_button)
        saveButton.setOnClickListener {
            importTextContent()
        }
    }

    private fun importTextContent() {
        val content = textInput.text.toString().trim()
        if (content.isEmpty()) {
            showSnackbar(getString(R.string.please_enter_text_content))
            return
        }
        val context = this
        launchCatchingTask {
            val tempFile = createTempCsvFile(content)
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "$packageName.apkgfileprovider",
                    tempFile,
                )
            val intent = Intent().setData(uri)

            onSelectedCsvForImport(intent)
            finish()
        }
    }

    private suspend fun createTempCsvFile(content: String): File =
        withContext(Dispatchers.IO) {
            val tempFile = File.createTempFile("import_text", ".csv", cacheDir)
            tempFile.writeText(content)
            tempFile
        }
}
