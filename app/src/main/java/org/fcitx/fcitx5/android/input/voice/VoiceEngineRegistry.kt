/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

object VoiceEngineRegistry {

    private val engines = mutableMapOf<String, SpeechRecognitionEngine>()

    fun register(engine: SpeechRecognitionEngine) {
        engines[engine.id] = engine
    }

    fun getEngine(id: String): SpeechRecognitionEngine? = engines[id]

    fun listEngines(): List<SpeechRecognitionEngine> = engines.values.toList()

    fun getDefaultEngine(): SpeechRecognitionEngine? = engines.values.firstOrNull()
}
