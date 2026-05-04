/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import android.media.AudioRecord

interface AudioRecorder {
    val sampleRate: Int
    val bytesPerChunk: Int

    fun start(): AudioRecord
    fun stop()
}
