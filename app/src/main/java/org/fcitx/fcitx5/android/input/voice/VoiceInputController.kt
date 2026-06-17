/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber

class VoiceInputController(
    private val context: Context,
    private val service: FcitxInputMethodService,
    private val engine: SpeechRecognitionEngine
) {

    private val prefs = AppPrefs.getInstance()

    @Volatile private var running = false
    private var stableText: String = ""
    private var currentPartial: String = ""
    private var commitOnStop: Boolean = false
    @Volatile private var finalized: Boolean = false
    @Volatile private var lastShownText: String = ""
    @Volatile private var committedText: String = ""

    private val recorder: AudioRecorder = Pcm16kAudioRecorder()
    private var recordInstance: AudioRecord? = null
    private var recordingJob: Job? = null
    private var stopTimeoutDeferred: Deferred<Unit>? = null

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

        if (engine.requiresPermission &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            service.showDialog(
                AlertDialog.Builder(context)
                    .setTitle(R.string.voice_permission_required)
                    .setMessage(R.string.voice_permission_required_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            )
            return
        }

        val cfg = loadConfig() ?: run {
            service.showDialog(
                AlertDialog.Builder(context)
                    .setTitle(R.string.voice_input)
                    .setMessage(
                        "Missing voice engine configuration. Please fill required settings."
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
            )
            return
        }
        service.finishComposing()
        stopTimeoutDeferred?.cancel()
        stopTimeoutDeferred = null
        running = true
        service.updateVoiceInputActive(true)
        stableText = ""
        currentPartial = ""
        commitOnStop = true
        finalized = false
        lastShownText = ""
        committedText = ""
        service.updateVoiceInputStatus(VoiceInputUiState.Connecting)

        startAudioRecording()

        engine.start(cfg) { event ->
            if (!running) return@start
            when (event) {
                is VoiceInputEvent.Started -> {
                    Timber.d("Voice input WebSocket connected")
                    service.updateVoiceInputStatus(VoiceInputUiState.Listening)
                }

                is VoiceInputEvent.Partial -> {

                    val fullText = event.text
                    currentPartial = fullText
                    lastShownText = fullText

                    service.updateVoiceInputStatus(VoiceInputUiState.Recognizing(fullText))

                    val partialText = if (fullText.length > committedText.length &&
                        fullText.startsWith(committedText)
                    ) {
                        fullText.substring(committedText.length)
                    } else {
                        fullText
                    }
                    service.lifecycleScope.launch {
                        if (!running) return@launch
                        service.updateComposingFromExternal(partialText)
                    }
                }

                is VoiceInputEvent.Final -> {
                    val finalText = event.text
                    stableText = if (finalText.startsWith(stableText)) {
                        finalText
                    } else {
                        stableText + finalText
                    }
                    currentPartial = ""
                    lastShownText = stableText

                    service.updateVoiceInputStatus(VoiceInputUiState.Recognizing(stableText))

                    if (stableText.length > committedText.length) {
                        val delta = stableText.substring(committedText.length)
                        committedText = stableText
                        service.lifecycleScope.launch {
                            service.commitText(delta)
                        }
                    }
                }

                is VoiceInputEvent.Error -> {
                    Timber.w("Voice input error: ${event.message}")
                    stopRecording()
                    service.lifecycleScope.launch {
                        service.updateComposingFromExternal("")
                    }
                    running = false
                    service.updateVoiceInputActive(false)
                    service.updateVoiceInputStatus(VoiceInputUiState.Error(event.message))
                }

                is VoiceInputEvent.Stopped -> {
                    stopRecording()
                    service.lifecycleScope.launch {
                        if (!finalized) {
                            if (commitOnStop) {
                                val t = lastShownText.ifBlank { stableText + currentPartial }
                                if (t.length > committedText.length) {
                                    val delta = t.substring(committedText.length)
                                    service.commitText(delta)
                                    committedText = t
                                }
                            }
                            stableText = ""
                            currentPartial = ""
                            commitOnStop = false
                            finalized = true
                            service.updateComposingFromExternal("")
                        }
                    }
                    running = false
                    service.updateVoiceInputActive(false)
                    service.updateVoiceInputStatus(VoiceInputUiState.Idle)
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        if (finalized) return
        finalized = true
        stopRecording()
        running = false
        service.updateVoiceInputActive(false)
        engine.stop()
        service.lifecycleScope.launch {
            val t = lastShownText.ifBlank { stableText + currentPartial }
            if (t.length > committedText.length) {
                val delta = t.substring(committedText.length)
                service.commitText(delta)
                committedText = t
            }
            stableText = ""
            currentPartial = ""
            commitOnStop = false
            service.updateComposingFromExternal("")
        }
        service.updateVoiceInputStatus(VoiceInputUiState.Idle)
        stopTimeoutDeferred?.cancel()
        stopTimeoutDeferred = null
    }

    fun cancel() {
        if (!running) return
        if (finalized) return
        running = false
        service.updateVoiceInputActive(false)
        finalized = true
        commitOnStop = false
        stopRecording()
        engine.cancel()
        stableText = ""
        currentPartial = ""
        service.lifecycleScope.launch {
            service.updateComposingFromExternal("")
        }
        service.updateVoiceInputStatus(VoiceInputUiState.Idle)
        stopTimeoutDeferred?.cancel()
        stopTimeoutDeferred = null
    }

    fun onPanelHidden() {
        cancel()
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioRecording() {
        if (!running) return
        recordingJob = service.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val record = recorder.start()
                recordInstance = record
                val buf = ByteArray(recorder.bytesPerChunk)
                Timber.d("Audio recording started, buffer size=%d", buf.size)

                while (isActive && running) {
                    val bytesRead = record.read(buf, 0, buf.size)
                    if (bytesRead > 0) {
                        val chunk = if (bytesRead == buf.size) buf else buf.copyOf(bytesRead)
                        engine.sendAudio(chunk)
                    }
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                Timber.e(e, "Audio recording error")
            } finally {
                Timber.d("Audio recording stopped")
                val r = recordInstance
                recordInstance = null
                if (r != null) {
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
        }
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        val r = recordInstance
        recordInstance = null
        if (r != null) {
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


    private fun loadConfig(): EngineConfig? {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return engine.loadConfig(sp)
    }
}
