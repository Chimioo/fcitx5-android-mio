/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice.engines.iflytek

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.EngineConfig
import org.fcitx.fcitx5.android.input.voice.SpeechRecognitionEngine
import org.fcitx.fcitx5.android.input.voice.VoiceInputEvent
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 讯飞标准版实时语音转写（RTASR）Engine
 *
 * https://www.xfyun.cn/doc/asr/rtasr/API.html
 */
class IflytekStdSpeechEngine : SpeechRecognitionEngine {

    override val id: String = "iflytek_std"
    override val displayNameRes: Int = R.string.voice_engine_iflytek_std
    override val requiresPermission: Boolean = true

    override val preferenceKeys: List<String> = listOf(
        "iflytek_std_app_id",
        "iflytek_std_api_key",
        "iflytek_std_lang",
    )

    override fun loadConfig(sp: SharedPreferences): IflytekStdConfig? {
        val appId = sp.getString("iflytek_std_app_id", "")?.trim().orEmpty()
        val apiKey = sp.getString("iflytek_std_api_key", "")?.trim().orEmpty()
        val lang = sp.getString("iflytek_std_lang", "cn")?.trim().orEmpty()
            .ifBlank { "cn" }

        if (appId.isBlank() || apiKey.isBlank()) return null

        return IflytekStdConfig(
            appId = appId,
            apiKey = apiKey,
            lang = lang,
        )
    }

    private var client: IflytekStdWebSocketClient? = null
    private val isRunning = AtomicBoolean(false)

    override fun start(config: EngineConfig, onEvent: (VoiceInputEvent) -> Unit) {
        if (!isRunning.compareAndSet(false, true)) {
            Timber.w("IflytekStdSpeechEngine: start() while already running, ignoring")
            return
        }

        val iflytekStdConfig = config as? IflytekStdConfig
            ?: run {
                isRunning.set(false)
                throw IllegalArgumentException("IflytekStdSpeechEngine requires IflytekStdConfig")
            }

        client?.cancel { }
        val newClient = IflytekStdWebSocketClient()
        client = newClient

        newClient.start(iflytekStdConfig) { event ->
            if (client !== newClient) {
                Timber.d("IflytekStdSpeechEngine: dropping stale event: $event")
                return@start
            }

            val mapped: VoiceInputEvent = when (event) {
                is IflytekStdWebSocketClient.Event.Started -> {
                    VoiceInputEvent.Started
                }

                is IflytekStdWebSocketClient.Event.Partial -> {
                    VoiceInputEvent.Partial(event.text)
                }

                is IflytekStdWebSocketClient.Event.Final -> {
                    VoiceInputEvent.Final(event.text)
                }

                is IflytekStdWebSocketClient.Event.Error -> {
                    isRunning.set(false)
                    Timber.w("IflytekStdSpeechEngine: error [${event.code}] ${event.message}")
                    VoiceInputEvent.Error("${event.code}: ${event.message}")
                }

                is IflytekStdWebSocketClient.Event.Stopped -> {
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
