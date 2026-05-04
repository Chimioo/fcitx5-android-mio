/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice.engines.doubao

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.EngineConfig
import org.fcitx.fcitx5.android.input.voice.SpeechRecognitionEngine
import org.fcitx.fcitx5.android.input.voice.VoiceInputEvent
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/*
 * 豆包（ByteDance Volcano Engine SAUC）语音识别引擎
 * 鉴权方式：X-Api-App-Key + X-Api-Access-Key + X-Api-Resource-Id
 */
class DoubaoSpeechEngine : SpeechRecognitionEngine {

    override val id: String = "doubao"
    override val displayNameRes: Int = R.string.voice_engine_doubao
    override val requiresPermission: Boolean = true

    override val preferenceKeys: List<String> = listOf(
        "doubao_api_key",
        "doubao_resource_id",
    )

    override fun loadConfig(sp: SharedPreferences): DoubaoConfig? {
        val apiKey = sp.getString("doubao_api_key", "")?.trim().orEmpty()
        val resourceId = sp.getString("doubao_resource_id", "volc.bigasr.sauc.duration")
            ?.trim().orEmpty().ifBlank { "volc.bigasr.sauc.duration" }

        if (apiKey.isBlank()) return null

        return DoubaoConfig(
            apiKey = apiKey,
            resourceId = resourceId,
        )
    }

    private var client: DoubaoWebSocketClient? = null
    private val isRunning = AtomicBoolean(false)

    override fun start(config: EngineConfig, onEvent: (VoiceInputEvent) -> Unit) {
        if (!isRunning.compareAndSet(false, true)) {
            Timber.w("DoubaoSpeechEngine: start() while already running, ignoring")
            return
        }

        val doubaoConfig = config as? DoubaoConfig
            ?: run {
                isRunning.set(false)
                throw IllegalArgumentException("DoubaoSpeechEngine requires DoubaoConfig")
            }

        client?.cancel { }
        val newClient = DoubaoWebSocketClient()
        client = newClient

        newClient.start(doubaoConfig) { event ->
            if (client !== newClient) {
                Timber.d("DoubaoSpeechEngine: dropping stale event: $event")
                return@start
            }

            val mapped: VoiceInputEvent = when (event) {
                is DoubaoWebSocketClient.Event.Started -> {
                    VoiceInputEvent.Started
                }

                is DoubaoWebSocketClient.Event.Partial -> {
                    VoiceInputEvent.Partial(event.text)
                }

                is DoubaoWebSocketClient.Event.Final -> {
                    VoiceInputEvent.Final(event.text)
                }

                is DoubaoWebSocketClient.Event.Error -> {
                    isRunning.set(false)
                    Timber.w("DoubaoSpeechEngine: error [${event.code}] ${event.message}")
                    VoiceInputEvent.Error("${event.code}: ${event.message}")
                }

                is DoubaoWebSocketClient.Event.Stopped -> {
                    isRunning.set(false)
                    VoiceInputEvent.Stopped
                }
            }

            onEvent(mapped)
        }
    }

    override fun sendAudio(pcmData: ByteArray) {
        if (!isRunning.get()) return
        client?.sendAudioFrame(pcmData)
    }

    override fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        client?.stop()
    }

    override fun cancel() {
        if (!isRunning.compareAndSet(true, false)) return
        val current = client
        client = null
        current?.cancel { }
    }
}
