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

@Suppress("ktlint:standard:property-naming", "ktlint:standard:function-naming")
class WhisperEngine {
    private var handle: Long = 0

    fun init(model_path: String): Boolean {
        close()
        handle = nativeInit(model_path)
        return handle != 0L
    }

    fun transcribe_pcm16(
        pcm: ShortArray,
        sample_rate: Int = 16000,
        language: String = "zh",
    ): String {
        val h = handle
        if (h == 0L) return ""
        return nativeTranscribePcm16(h, pcm, sample_rate, language)
    }

    fun close() {
        val h = handle
        if (h != 0L) {
            nativeFree(h)
            handle = 0
        }
    }

    private external fun nativeInit(model_path: String): Long

    private external fun nativeFree(handle: Long)

    private external fun nativeTranscribePcm16(
        handle: Long,
        pcm: ShortArray,
        sample_rate: Int,
        language: String,
    ): String

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
