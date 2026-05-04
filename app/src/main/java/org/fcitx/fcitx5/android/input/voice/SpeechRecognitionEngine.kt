/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import android.content.SharedPreferences

interface SpeechRecognitionEngine {
    val id: String
    @get:androidx.annotation.StringRes val displayNameRes: Int
    val requiresPermission: Boolean
        get() = true


    val preferenceKeys: List<String>


    fun loadConfig(sp: SharedPreferences): EngineConfig?

    fun start(config: EngineConfig, onEvent: (VoiceInputEvent) -> Unit)


    fun sendAudio(pcmData: ByteArray)

    fun stop()
    fun cancel()
}

interface EngineConfig
