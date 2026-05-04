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
 * 讯飞实时语音转写大模型（RTASR LLM）Engine
 *
 * https://www.xfyun.cn/doc/spark/asr_llm/rtasr_llm.html
 */
class IflytekSpeechEngine : SpeechRecognitionEngine {

    override val id: String = "iflytek"
    override val displayNameRes: Int = R.string.voice_engine_iflytek_llm
    override val requiresPermission: Boolean = true

    override val preferenceKeys: List<String> = listOf(
        "iflytek_app_id",
        "iflytek_access_key_id",
        "iflytek_access_key_secret",
        "iflytek_lang",
    )

    override fun loadConfig(sp: SharedPreferences): IflytekAstConfig? {
        val appId = sp.getString("iflytek_app_id", "")?.trim().orEmpty()
        val accessKeyId = sp.getString("iflytek_access_key_id", "")?.trim().orEmpty()
        val accessKeySecret = sp.getString("iflytek_access_key_secret", "")?.trim().orEmpty()
        val lang = sp.getString("iflytek_lang", "autodialect")?.trim().orEmpty()
            .ifBlank { "autodialect" }

        if (appId.isBlank() || accessKeyId.isBlank() || accessKeySecret.isBlank()) return null

        return IflytekAstConfig(
            appId = appId,
            accessKeyId = accessKeyId,
            accessKeySecret = accessKeySecret,
            lang = when (lang.lowercase()) {
                "autodialect", "auto" -> IflytekAstConfig.Lang.AutoDialect
                "autominor" -> IflytekAstConfig.Lang.AutoMinor
                else -> IflytekAstConfig.Lang.AutoDialect
            },
        )
    }

    private var client: RtasrLlmWebSocketClient? = null
    private val isRunning = AtomicBoolean(false)

    override fun start(config: EngineConfig, onEvent: (VoiceInputEvent) -> Unit) {
        if (!isRunning.compareAndSet(false, true)) {
            Timber.w("IflytekSpeechEngine: start() while already running, ignoring")
            return
        }

        val iflytekConfig = config as? IflytekAstConfig
            ?: run {
                isRunning.set(false)
                throw IllegalArgumentException("IflytekSpeechEngine requires IflytekAstConfig")
            }

        client?.cancel { }
        val newClient = RtasrLlmWebSocketClient()
        client = newClient

        val rtasrConfig = RtasrLlmConfig(
            appId = iflytekConfig.appId,
            accessKeyId = iflytekConfig.accessKeyId,
            accessKeySecret = iflytekConfig.accessKeySecret,
            lang = when (iflytekConfig.lang) {
                IflytekAstConfig.Lang.AutoDialect -> RtasrLlmConfig.Lang.AutoDialect
                IflytekAstConfig.Lang.AutoMinor -> RtasrLlmConfig.Lang.AutoMinor
            },
            audioEncode = when (iflytekConfig.audioEncode) {
                IflytekAstConfig.AudioEncode.Pcm -> RtasrLlmConfig.AudioEncode.Pcm
                IflytekAstConfig.AudioEncode.OpusWb -> RtasrLlmConfig.AudioEncode.OpusWb
                IflytekAstConfig.AudioEncode.Speex7 -> RtasrLlmConfig.AudioEncode.Speex7
                IflytekAstConfig.AudioEncode.Speex10 -> RtasrLlmConfig.AudioEncode.Speex10
            },
            sampleRate = iflytekConfig.sampleRate,
            pd = iflytekConfig.pd?.let { pd ->
                RtasrLlmConfig.Domain.valueOf(pd.name)
            },
            roleType = when (iflytekConfig.roleType) {
                IflytekAstConfig.RoleType.Disabled -> RtasrLlmConfig.RoleType.Disabled
                IflytekAstConfig.RoleType.Blind -> RtasrLlmConfig.RoleType.Blind
            },
            engPunc = iflytekConfig.engPunc,
            engVadMdn = when (iflytekConfig.engVadMdn) {
                IflytekAstConfig.VadMode.Far -> RtasrLlmConfig.VadMode.Far
                IflytekAstConfig.VadMode.Near -> RtasrLlmConfig.VadMode.Near
            },
        )

        newClient.start(rtasrConfig) { event ->

            if (client !== newClient) {
                Timber.d("IflytekSpeechEngine: dropping stale event: $event")
                return@start
            }

            val mapped: VoiceInputEvent = when (event) {
                is RtasrLlmWebSocketClient.Event.Started -> {
                    VoiceInputEvent.Started
                }

                is RtasrLlmWebSocketClient.Event.Partial -> {

                    VoiceInputEvent.Partial(event.text)
                }

                is RtasrLlmWebSocketClient.Event.Final -> {

                    VoiceInputEvent.Final(event.text)
                }

                is RtasrLlmWebSocketClient.Event.Error -> {
                    isRunning.set(false)
                    Timber.w("IflytekSpeechEngine: error [${event.code}] ${event.message}")
                    VoiceInputEvent.Error("${event.code}: ${event.message}")
                }

                is RtasrLlmWebSocketClient.Event.Stopped -> {
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
        stopWithLastAudio(null)
    }

    fun stopWithLastAudio(lastPcmData: ByteArray?) {
        if (!isRunning.compareAndSet(true, false)) return
        lastPcmData?.let { client?.sendAudioFrame(it) }
        client?.stop()
    }


    override fun cancel() {
        if (!isRunning.compareAndSet(true, false)) return
        val current = client
        client = null
        current?.cancel { }
    }
}
