
/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class IflytekAstWebSocketClient(
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    sealed class Event {
        data object Started : Event()
        data class Partial(val text: String) : Event()
        data class Final(val text: String) : Event()
        data class Error(val message: String) : Event()
        data object Stopped : Event()
    }

    private var socket: WebSocket? = null
    private var sessionId: String? = null
    private var scope: CoroutineScope? = null
    private var audioJob: Job? = null
    private var recorder: Pcm16kAudioRecorder? = null
    private val pendingAudioChunks = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var wsOpened: Boolean = false

    fun start(config: IflytekAstConfig, onEvent: (Event) -> Unit) {
        if (socket != null) return

        wsOpened = false
        pendingAudioChunks.clear()

        val sid = UUID.randomUUID().toString()
        sessionId = sid

        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val url = IflytekAstSigner.buildSignedUrl(
            config = config,
            utc = utc,
            sessionId = sid,
            samplerate = Pcm16kAudioRecorder.SAMPLE_RATE,
            audioEncode = "pcm_s16le"
        )

        val localRecorder = Pcm16kAudioRecorder()
        recorder = localRecorder

        // Start recording immediately to reduce perceived latency. Audio will be buffered
        // until WebSocket connection is opened.
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        audioJob = s.launch {
            val r = localRecorder.start()
            val buffer = ByteArray(Pcm16kAudioRecorder.BYTES_PER_CHUNK)
            try {
                while (isActive) {
                    val n = r.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val chunk = buffer.copyOf(n)
                    val ws = socket
                    if (ws != null && wsOpened) {
                        // Flush any buffered audio first.
                        while (true) {
                            val p = pendingAudioChunks.poll() ?: break
                            ws.send(p.toByteString())
                        }
                        ws.send(chunk.toByteString())
                    } else {
                        // Buffer audio until WS is connected.
                        pendingAudioChunks.add(chunk)
                        // Prevent unbounded growth if network is slow.
                        while (pendingAudioChunks.size > 200) {
                            pendingAudioChunks.poll()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Iflytek audio loop failed")
            } finally {
                localRecorder.stop()
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("Iflytek WS opened: $response")
                wsOpened = true
                onEvent(Event.Started)
                // Buffered audio will be flushed by the recording loop.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                IflytekAstResultParser.parseError(text)?.let {
                    onEvent(Event.Error(it))
                    return
                }
                val r = IflytekAstResultParser.parse(text) ?: return
                if (r.text.isBlank()) return
                if (r.isFinal) {
                    onEvent(Event.Final(r.text))
                } else {
                    onEvent(Event.Partial(r.text))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "Iflytek WS failure response=$response")
                onEvent(Event.Error(t.message ?: "WebSocket failure"))
                stopInternal(onEvent, sendEnd = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("Iflytek WS closed code=$code reason=$reason")
                stopInternal(onEvent, sendEnd = false)
            }
        }

        val req = Request.Builder().url(url).build()
        socket = httpClient.newWebSocket(req, listener)
    }

    fun stop(onEvent: (Event) -> Unit) {
        stopInternal(onEvent, sendEnd = true)
    }

    fun cancel(onEvent: (Event) -> Unit) {
        stopInternal(onEvent, sendEnd = false)
    }

    private fun stopInternal(onEvent: (Event) -> Unit, sendEnd: Boolean) {
        val ws = socket ?: return
        val sid = sessionId
        socket = null
        sessionId = null
        wsOpened = false
        pendingAudioChunks.clear()

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder = null

        try {
            if (sendEnd && sid != null) {
                val end = JSONObject().apply {
                    put("end", true)
                    put("sessionId", sid)
                }
                ws.send(end.toString())
            }
        } catch (_: Exception) {
        }

        try {
            ws.close(1000, "bye")
        } catch (_: Exception) {
        }

        audioJob?.cancel()
        audioJob = null

        scope?.cancel()
        scope = null

        onEvent(Event.Stopped)
    }
}
