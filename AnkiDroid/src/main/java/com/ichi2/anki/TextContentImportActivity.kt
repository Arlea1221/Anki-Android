@file:Suppress(
    "ktlint:standard:property-naming",
    "ktlint:standard:function-naming",
    "ktlint:standard:chain-method-continuation",
    "ktlint:standard:indent",
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
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.LineBackgroundSpan
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.pages.CsvImporter
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.utils.ImportUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("ktlint:standard:property-naming", "ktlint:standard:function-naming")
class TextContentImportActivity : AnkiActivity() {
    private lateinit var text_input: EditText
    private lateinit var gutter_container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_content_import)

        setup_text_input()
        setup_gutter()
    }

    private fun setup_text_input() {
        text_input = findViewById(R.id.text_input)
        text_input.hint = getString(R.string.text_content_import_hint)
        text_input.setLineSpacing(dp_float(6), 1f)

        val clipboard_manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip_data = clipboard_manager.primaryClip
        if (clip_data != null && clip_data.itemCount > 0) {
            val clip_text = clip_data.getItemAt(0).text
            if (!clip_text.isNullOrBlank()) {
                text_input.setText(clip_text.toString())
                text_input.setSelection(text_input.text.length)
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

    private fun setup_gutter() {
        gutter_container = findViewById(R.id.gutter_container)

        text_input.post {
            val gutter_width = dp(64)
            text_input.setPaddingRelative(
                gutter_width,
                text_input.paddingTop,
                text_input.paddingEnd,
                text_input.paddingBottom,
            )
            gutter_container.setPadding(0, text_input.paddingTop, 0, 0)
            rebuild_gutter()
        }

        text_input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                }

                override fun afterTextChanged(s: Editable?) {
                    rebuild_gutter()
                }
            },
        )

        text_input.setOnScrollChangeListener { _, _, _, _, _ -> sync_gutter_scroll() }
    }

    private val rebuild_runnable = Runnable { perform_rebuild_gutter() }

    private fun rebuild_gutter() {
        text_input.removeCallbacks(rebuild_runnable)
        text_input.post(rebuild_runnable)
    }

    private fun perform_rebuild_gutter() {
        val layout = text_input.layout ?: return
        val lines = text_input.text.toString().split("\n")
        val duplicate_indices = find_duplicate_indices(lines)

        gutter_container.removeAllViews()
        update_line_decorations(lines, duplicate_indices)

        var char_offset = 0
        for ((idx, line) in lines.withIndex()) {
            val safe_start = char_offset.coerceAtMost(text_input.text.length)
            val safe_end = (char_offset + line.length).coerceAtMost(text_input.text.length)
            val display_line_start = layout.getLineForOffset(safe_start)
            val display_line_end = layout.getLineForOffset(safe_end)
            val top = layout.getLineTop(display_line_start)
            val bottom = layout.getLineBottom(display_line_end)

            gutter_container.addView(
                create_gutter_row(
                    line_idx = idx,
                    height = bottom - top,
                    is_duplicate = duplicate_indices.contains(idx),
                    show_divider = idx != lines.lastIndex,
                ),
            )
            char_offset += line.length + 1
        }

        sync_gutter_scroll()
    }

    private fun create_gutter_row(
        line_idx: Int,
        height: Int,
        is_duplicate: Boolean,
        show_divider: Boolean,
    ): LinearLayout {
        val row_container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height)
            }

        val row_content =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        0,
                        1f,
                    )
                setPadding(dp(4), 0, 0, 0)
            }

        val delete_btn =
            TextView(this).apply {
                text = "×"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor("#999999".toColorInt())
                val size = dp(22)
                layoutParams = LinearLayout.LayoutParams(size, size)
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
setColor("#F0F0F0".toColorInt())
        setStroke(dp(1), "#DDDDDD".toColorInt())
                    }
                isClickable = true
                isFocusable = true
                setOnClickListener { delete_line(line_idx) }
            }

        val line_num =
            TextView(this).apply {
                text = (line_idx + 1).toString()
                textSize = 11f
                setTextColor(if (is_duplicate) DUPLICATE_TEXT_COLOR else NORMAL_TEXT_COLOR)
                if (is_duplicate) {
                    setTypeface(null, Typeface.BOLD)
                }
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(26),
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    ).apply {
                        marginStart = dp(4)
                    }
            }

        row_content.addView(delete_btn)
        row_content.addView(line_num)
        row_container.addView(row_content)

        if (show_divider) {
            row_container.addView(
                TextView(this).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1),
                        )
                    setBackgroundColor(LINE_DIVIDER_COLOR)
                },
            )
        }

        return row_container
    }

    private fun find_duplicate_indices(lines: List<String>): Set<Int> {
        val count_map = mutableMapOf<String, Int>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                count_map[trimmed] = (count_map[trimmed] ?: 0) + 1
            }
        }

        return lines.indices
            .filter { idx ->
                val trimmed = lines[idx].trim()
                trimmed.isNotEmpty() && (count_map[trimmed] ?: 0) > 1
            }.toSet()
    }

    private fun update_line_decorations(
        lines: List<String>,
        duplicate_indices: Set<Int>,
    ) {
        val editable = text_input.text
        editable.getSpans(0, editable.length, DuplicateLineSpan::class.java)
            .forEach { editable.removeSpan(it) }
        editable.getSpans(0, editable.length, LineDividerSpan::class.java)
            .forEach { editable.removeSpan(it) }

        var char_offset = 0
        for ((idx, line) in lines.withIndex()) {
            val start = char_offset.coerceAtMost(editable.length)
            val content_end = (char_offset + line.length).coerceAtMost(editable.length)
            val span_end = resolve_line_span_end(start, content_end, editable.length)

            if (span_end != null) {
                if (duplicate_indices.contains(idx)) {
                    editable.setSpan(
                        DuplicateLineSpan(DUP_HIGHLIGHT_COLOR),
                        start,
                        span_end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                if (idx != lines.lastIndex) {
                    editable.setSpan(
                        LineDividerSpan(LINE_DIVIDER_COLOR, dp(1)),
                        start,
                        span_end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }

            char_offset += line.length + 1
        }
    }

    private fun resolve_line_span_end(
        start: Int,
        content_end: Int,
        text_length: Int,
    ): Int? =
        when {
            start < content_end -> content_end
            start < text_length -> start + 1
            else -> null
        }

    private fun delete_line(line_idx: Int) {
        val lines = text_input.text.toString().split("\n").toMutableList()
        if (line_idx !in lines.indices) {
            return
        }

        var cursor_pos = 0
        for (i in 0 until line_idx) {
            cursor_pos += lines[i].length + 1
        }

        lines.removeAt(line_idx)
        val new_text = lines.joinToString("\n")
        text_input.setText(new_text)
        text_input.setSelection(cursor_pos.coerceAtMost(new_text.length))
    }

    private fun sync_gutter_scroll() {
        gutter_container.translationY = -text_input.scrollY.toFloat()
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

        launchCatchingTask {
            intent.getLongExtra(EXTRA_TARGET_DECK_ID, 0L)
                .takeIf { it != 0L }
                ?.let { targetDeckId -> withCol { decks.select(targetDeckId) } }

            val temp_file = create_temp_csv_file(content)
            val uri =
                FileProvider.getUriForFile(
                    this@TextContentImportActivity,
                    "$packageName.apkgfileprovider",
                    temp_file,
                )
            val import_intent = Intent().setData(uri)

            if (open_csv_importer(import_intent)) {
                finish()
            }
        }
    }

    private fun open_csv_importer(data: Intent): Boolean {
        val path = ImportUtils.getFileCachedCopy(this, data) ?: return false
        startActivity(CsvImporter.getIntent(this, path))
        return true
    }

    private suspend fun create_temp_csv_file(content: String): File =
        withContext(Dispatchers.IO) {
            val temp_file = File.createTempFile("import_text", ".csv", cacheDir)
            temp_file.writeText(content)
            temp_file
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp_float(value: Int): Float = value * resources.displayMetrics.density

    class DuplicateLineSpan(
        private val color: Int,
    ) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int,
        ) {
            val original_color = paint.color
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = original_color
        }
    }

    class LineDividerSpan(
        private val color: Int,
        private val thickness: Int,
    ) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int,
        ) {
            val original_color = paint.color
            paint.color = color
            canvas.drawRect(
                left.toFloat(),
                (bottom - thickness).toFloat(),
                right.toFloat(),
                bottom.toFloat(),
                paint,
            )
            paint.color = original_color
        }
    }

    companion object {
        const val EXTRA_TARGET_DECK_ID = "extra_target_deck_id"
    private val DUP_HIGHLIGHT_COLOR = "#26FF4D4F".toColorInt()
    private val NORMAL_TEXT_COLOR = "#B0B0B0".toColorInt()
    private val DUPLICATE_TEXT_COLOR = "#FF4D4F".toColorInt()
    private val LINE_DIVIDER_COLOR = "#14000000".toColorInt()
    }
}
