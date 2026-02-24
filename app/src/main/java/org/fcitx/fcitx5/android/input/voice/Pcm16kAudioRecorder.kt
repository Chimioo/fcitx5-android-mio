/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class Pcm16kAudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        const val BYTES_PER_CHUNK = 1280
    }

    private var record: AudioRecord? = null

    fun start(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(min, BYTES_PER_CHUNK * 4)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        r.startRecording()
        record = r
        return r
    }

    fun stop() {
        val r = record ?: return
        record = null
        try {
            r.stop()
        } catch (_: Exception) {
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
    }
}
