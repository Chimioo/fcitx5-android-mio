/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber

class VoiceInputController(
    private val context: Context,
    private val service: FcitxInputMethodService
) {

    private val prefs = AppPrefs.getInstance()
    private val client = IflytekAstWebSocketClient()

    private var running = false
    private var stableText: String = ""
    private var currentPartial: String = ""
    private var commitOnStop: Boolean = false
    private var finalized: Boolean = false
    @Volatile private var lastShownText: String = ""
    @Volatile private var committedText: String = ""

    fun isRunning(): Boolean = running

    fun toggle() {
        if (running) {
            stop()
        } else {
            start()
        }
    }

    fun start() {
        if (running) return

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            service.showDialog(
                AlertDialog.Builder(context)
                    .setTitle(R.string.iflytek_permission_required)
                    .setMessage(R.string.iflytek_permission_required_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            )
            return
        }

        val enabled = prefs.voice.enableIflytekVoiceInput.getValue()
        if (!enabled) return

        val cfg = loadConfig() ?: run {
            service.showDialog(
                AlertDialog.Builder(context)
                    .setTitle(R.string.iflytek_voice_input)
                    .setMessage(
                        "Missing iFlytek configuration. Please fill AppId/ApiKey/ApiSecret in settings."
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            )
            return
        }
        running = true
        stableText = ""
        currentPartial = ""
        commitOnStop = true
        finalized = false
        lastShownText = ""
        committedText = ""
        service.onIflytekVoiceInputRunningChanged(true)

        client.start(cfg) {
            when (it) {
                is IflytekAstWebSocketClient.Event.Started -> {
                    Timber.d("Iflytek voice started")
                }
                is IflytekAstWebSocketClient.Event.Partial -> {
                    val newPartial = it.text
                    if (
                        currentPartial.isNotBlank() &&
                        newPartial.isNotBlank() &&
                        newPartial.length < currentPartial.length &&
                        !newPartial.startsWith(currentPartial)
                    ) {
                        stableText += currentPartial
                    }
                    currentPartial = newPartial
                    val t = stableText + currentPartial
                    lastShownText = t
                    service.lifecycleScope.launch {
                        service.updateComposingFromExternal(t)
                    }
                }
                is IflytekAstWebSocketClient.Event.Final -> {
                    val finalText = it.text
                    stableText = if (finalText.startsWith(stableText)) {
                        finalText
                    } else {
                        stableText + finalText
                    }
                    currentPartial = ""
                    lastShownText = stableText
                    service.lifecycleScope.launch {
                        service.updateComposingFromExternal(stableText)
                    }

                    // If we already finalized on stop, but a longer final result arrives after that,
                    // append the missing suffix instead of shrinking text.
                    if (finalized && committedText.isNotBlank() && stableText.length > committedText.length) {
                        val suffix = stableText.substring(committedText.length)
                        committedText = stableText
                        service.lifecycleScope.launch {
                            service.commitText(suffix)
                        }
                    }
                }
                is IflytekAstWebSocketClient.Event.Error -> {
                    Timber.w("Iflytek voice error: ${it.message}")
                    service.lifecycleScope.launch {
                        service.finishComposing()
                    }
                    running = false
                    service.onIflytekVoiceInputRunningChanged(false)
                }
                is IflytekAstWebSocketClient.Event.Stopped -> {
                    service.lifecycleScope.launch {
                        if (!finalized) {
                            if (commitOnStop) {
                                val t = lastShownText.ifBlank { stableText + currentPartial }
                                if (t.isNotBlank()) {
                                    service.commitText(t)
                                    committedText = t
                                }
                            }
                            stableText = ""
                            currentPartial = ""
                            commitOnStop = false
                            finalized = true
                            service.finishComposing()
                        }
                    }
                    running = false
                    service.onIflytekVoiceInputRunningChanged(false)
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        val t = lastShownText.ifBlank { stableText + currentPartial }
        service.lifecycleScope.launch {
            if (!finalized) {
                if (t.isNotBlank()) {
                    service.commitText(t)
                    committedText = t
                }
                stableText = ""
                currentPartial = ""
                commitOnStop = false
                finalized = true
                service.finishComposing()
            }
        }
        commitOnStop = false
        client.stop { }
        running = false
        service.onIflytekVoiceInputRunningChanged(false)
    }

    fun cancel() {
        if (!running) return
        service.lifecycleScope.launch {
            if (!finalized) {
                stableText = ""
                currentPartial = ""
                commitOnStop = false
                finalized = true
                service.finishComposing()
            }
        }
        commitOnStop = false
        client.cancel { }
        running = false
        service.onIflytekVoiceInputRunningChanged(false)
    }

    fun onPanelHidden() {
        cancel()
    }

    private fun loadConfig(): IflytekAstConfig? {
        val appId = prefs.voice.iflytekAppId.getValue().trim()
        val apiKey = prefs.voice.iflytekApiKey.getValue().trim()
        val apiSecret = prefs.voice.iflytekApiSecret.getValue().trim()

        val ak = apiKey
        val sk = apiSecret
        val lang = prefs.voice.iflytekLang.getValue().trim().ifBlank { "autodialect" }
        val uuid = prefs.voice.iflytekUuid.getValue().trim().ifBlank { null }
        val pd = prefs.voice.iflytekPd.getValue().trim().ifBlank { null }

        if (appId.isBlank() || ak.isBlank() || sk.isBlank()) {
            return null
        }

        return IflytekAstConfig(
            appId = appId,
            apiKey = ak,
            apiSecret = sk,
            lang = lang,
            uuid = uuid,
            pd = pd
        )
    }
}
