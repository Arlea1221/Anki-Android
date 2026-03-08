/*
 * Copyright (c) 2025 Brayan Oliveira <69634269+brayandso@users.noreply.github.com>
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
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.time.getTimestamp
import com.ichi2.anki.databinding.DrawingFragmentBinding
import com.ichi2.anki.dialogs.DiscardChangesDialog
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardFragment
import com.ichi2.anki.ui.windows.reviewer.whiteboard.WhiteboardView
import com.ichi2.compat.CompatHelper
import com.ichi2.themes.Themes
import com.ichi2.utils.openInputStreamSafe
import dev.androidbroadcast.vbpd.viewBinding

class DrawingFragment : Fragment(R.layout.drawing_fragment) {
    private val binding by viewBinding(DrawingFragmentBinding::bind)
    private var has_background_image = false
    private var zoom_scale = MIN_ZOOM_SCALE
    private var zoom_translation_x = 0f
    private var zoom_translation_y = 0f
    private var last_focus_x = 0f
    private var last_focus_y = 0f
    private val whiteboard_fragment
        get() = childFragmentManager.findFragmentById(R.id.fragment_container) as? WhiteboardFragment

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.apply {
            setNavigationOnClickListener {
                // avoid showing the discard changes dialog only if the user hasn't drawn anything,
                // even if is is erased or undone, since they may want to undo/redo something.
                if (whiteboard_fragment?.isEmpty() == true) {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                } else {
                    DiscardChangesDialog.showDialog(requireContext()) {
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_save -> {
                        on_save_drawing()
                        true
                    }
                    else -> false
                }
            }
        }
        view.post {
            val whiteboard_view = whiteboard_fragment?.binding?.whiteboardView ?: return@post
            whiteboard_fragment?.set_force_stylus_mode_off(true)
            setup_background_image(whiteboard_view)
            setup_zoom_and_pan(whiteboard_view)
        }
    }

    private fun on_save_drawing() {
        val whiteboard_view = whiteboard_fragment?.binding?.whiteboardView ?: return
        reset_zoom_transform(whiteboard_view)
        val image_uri = save_whiteboard(whiteboard_view, has_background_image)
        val result =
            Intent().apply {
                putExtra(IMAGE_PATH_KEY, image_uri)
            }
        requireActivity().setResult(Activity.RESULT_OK, result)
        requireActivity().finish()
    }

    private fun save_whiteboard(
        view: WhiteboardView,
        has_background_image: Boolean,
    ): Uri {
        val bitmap = createBitmap(view.width, view.height)
        val canvas = Canvas(bitmap)

        if (!has_background_image) {
            val background_color =
                if (Themes.isNightTheme) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
            canvas.drawColor(background_color)
        }

        view.draw(canvas)

        val base_file_name = "Whiteboard" + getTimestamp(TimeManager.time)
        return CompatHelper.compat.saveImage(requireContext(), bitmap, base_file_name, "jpg", Bitmap.CompressFormat.JPEG, 95)
    }

    private fun setup_background_image(whiteboard_view: WhiteboardView) {
        val background_image_uri =
            arguments?.let {
                BundleCompat.getParcelable(it, BACKGROUND_IMAGE_URI_KEY, Uri::class.java)
            } ?: return

        val source_bitmap =
            requireContext().contentResolver.openInputStreamSafe(background_image_uri)?.use { input_stream ->
                BitmapFactory.decodeStream(input_stream)
            } ?: return

        if (whiteboard_view.width <= 0 || whiteboard_view.height <= 0) {
            return
        }

        val destination_rect =
            calculate_fit_center_rect(
                source_width = source_bitmap.width,
                source_height = source_bitmap.height,
                container_width = whiteboard_view.width,
                container_height = whiteboard_view.height,
            )
        (whiteboard_view.layoutParams as? FrameLayout.LayoutParams)?.let { layout_params ->
            layout_params.width = destination_rect.width()
            layout_params.height = destination_rect.height()
            layout_params.gravity = Gravity.CENTER
            whiteboard_view.layoutParams = layout_params
        }
        whiteboard_view.background = BitmapDrawable(resources, source_bitmap)
        has_background_image = true
    }

    private fun setup_zoom_and_pan(whiteboard_view: WhiteboardView) {
        val scale_gesture_detector =
            ScaleGestureDetector(
                requireContext(),
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val previous_scale = zoom_scale
                        zoom_scale = (zoom_scale * detector.scaleFactor).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
                        val applied_scale_factor = zoom_scale / previous_scale

                        zoom_translation_x =
                            detector.focusX - (detector.focusX - zoom_translation_x) * applied_scale_factor
                        zoom_translation_y =
                            detector.focusY - (detector.focusY - zoom_translation_y) * applied_scale_factor

                        last_focus_x = detector.focusX
                        last_focus_y = detector.focusY
                        apply_zoom_transform(whiteboard_view)
                        return true
                    }
                },
            )

        var is_multi_touch_active = false
        whiteboard_view.setOnTouchListener { _, event ->
            if (event.pointerCount >= 2) {
                is_multi_touch_active = true
                scale_gesture_detector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        last_focus_x = get_focus_x(event)
                        last_focus_y = get_focus_y(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!scale_gesture_detector.isInProgress && zoom_scale > MIN_ZOOM_SCALE) {
                            val current_focus_x = get_focus_x(event)
                            val current_focus_y = get_focus_y(event)
                            zoom_translation_x += current_focus_x - last_focus_x
                            zoom_translation_y += current_focus_y - last_focus_y
                            last_focus_x = current_focus_x
                            last_focus_y = current_focus_y
                            apply_zoom_transform(whiteboard_view)
                        }
                    }
                }
                return@setOnTouchListener true
            }

            if (is_multi_touch_active) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    is_multi_touch_active = false
                }
                // consume the tail events of a pinch gesture to avoid accidental strokes
                return@setOnTouchListener true
            }

            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                is_multi_touch_active = false
            }
            false
        }
    }

    private fun apply_zoom_transform(whiteboard_view: WhiteboardView) {
        if (zoom_scale <= MIN_ZOOM_SCALE + 0.001f) {
            zoom_scale = MIN_ZOOM_SCALE
            zoom_translation_x = 0f
            zoom_translation_y = 0f
        } else {
            val max_translation_x = (whiteboard_view.width * (zoom_scale - 1f)) / 2f
            val max_translation_y = (whiteboard_view.height * (zoom_scale - 1f)) / 2f
            zoom_translation_x = zoom_translation_x.coerceIn(-max_translation_x, max_translation_x)
            zoom_translation_y = zoom_translation_y.coerceIn(-max_translation_y, max_translation_y)
        }
        whiteboard_view.scaleX = zoom_scale
        whiteboard_view.scaleY = zoom_scale
        whiteboard_view.translationX = zoom_translation_x
        whiteboard_view.translationY = zoom_translation_y
    }

    private fun reset_zoom_transform(whiteboard_view: WhiteboardView) {
        zoom_scale = MIN_ZOOM_SCALE
        zoom_translation_x = 0f
        zoom_translation_y = 0f
        apply_zoom_transform(whiteboard_view)
    }

    private fun get_focus_x(event: MotionEvent): Float =
        if (event.pointerCount >= 2) {
            (event.getX(0) + event.getX(1)) / 2f
        } else {
            event.x
        }

    private fun get_focus_y(event: MotionEvent): Float =
        if (event.pointerCount >= 2) {
            (event.getY(0) + event.getY(1)) / 2f
        } else {
            event.y
        }

    private fun calculate_fit_center_rect(
        source_width: Int,
        source_height: Int,
        container_width: Int,
        container_height: Int,
    ): Rect {
        val source_ratio = source_width.toFloat() / source_height.toFloat()
        val container_ratio = container_width.toFloat() / container_height.toFloat()

        val destination_width: Int
        val destination_height: Int
        if (source_ratio > container_ratio) {
            destination_width = container_width
            destination_height = (container_width / source_ratio).toInt()
        } else {
            destination_height = container_height
            destination_width = (container_height * source_ratio).toInt()
        }

        val left = (container_width - destination_width) / 2
        val top = (container_height - destination_height) / 2
        return Rect(left, top, left + destination_width, top + destination_height)
    }

    companion object {
        const val IMAGE_PATH_KEY = "path"
        const val BACKGROUND_IMAGE_URI_KEY = "background_image_uri"
        private const val MIN_ZOOM_SCALE = 1f
        private const val MAX_ZOOM_SCALE = 5f

        fun getIntent(
            context: Context,
            background_image_uri: Uri? = null,
        ): Intent =
            SingleFragmentActivity.getIntent(
                context,
                DrawingFragment::class,
                background_image_uri?.let {
                    bundleOf(BACKGROUND_IMAGE_URI_KEY to it)
                },
            )
    }
}
