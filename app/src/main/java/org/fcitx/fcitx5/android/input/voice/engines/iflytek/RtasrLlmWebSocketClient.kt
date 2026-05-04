/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice.engines.iflytek

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RtasrLlmWebSocketClient {

    sealed class Event {
        object Started : Event()
        data class Partial(val text: String) : Event()
        data class Final(val text: String) : Event()
        data class Error(val code: String, val message: String) : Event()
        object Stopped : Event()
    }

    private var webSocket: WebSocket? = null
    private var onEvent: ((Event) -> Unit)? = null
    private var sessionId: String = ""
    private val seenSegIds = mutableSetOf<Int>()
    private val committedText = StringBuilder()
    private val isCancelled = AtomicBoolean(false)
    private val endFrameSent = AtomicBoolean(false)

    fun start(config: RtasrLlmConfig, onEvent: (Event) -> Unit) {
        reset()
        this.onEvent = onEvent
        this.sessionId = UUID.randomUUID().toString().replace("-", "")

        val url = buildAuthUrl(config, sessionId) ?: run {
            onEvent(Event.Error("URL_BUILD_FAILED", "Failed to build auth URL"))
            return
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, createListener())
    }

    fun sendAudioFrame(pcmData: ByteArray) {
        if (isCancelled.get() || endFrameSent.get()) return
        webSocket?.send(pcmData.toByteString())
    }

    fun stop() {
        if (isCancelled.get()) return
        if (!endFrameSent.compareAndSet(false, true)) return
        val ws = webSocket ?: return

        val endJson = JSONObject().apply {
            put("end", true)
            put("sessionId", sessionId)
        }.toString()

        ws.send(endJson)
        Timber.d("RtasrLlm: end frame sent (sessionId=$sessionId)")
    }

    fun cancel(onDone: () -> Unit) {
        isCancelled.set(true)
        onEvent = null
        webSocket?.close(1000, "cancelled")
        webSocket = null
        onDone()
    }

    private fun reset() {
        isCancelled.set(false)
        endFrameSent.set(false)
        seenSegIds.clear()
        committedText.clear()
        sessionId = ""
        onEvent = null
        webSocket?.close(1000, "reset")
        webSocket = null
    }

    private fun createListener() = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isCancelled.get()) return
            Timber.d("RtasrLlm: WebSocket opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isCancelled.get()) return
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Timber.w("RtasrLlm: received unexpected binary message")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isCancelled.get()) return
            val msg = t.message ?: "Unknown WebSocket error"
            Timber.e(t, "RtasrLlm: WebSocket failure")
            emit(Event.Error("WS_FAILURE", msg))
            emit(Event.Stopped)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("RtasrLlm: WebSocket closed (code=$code reason=$reason)")
        }
    }

    private fun handleMessage(rawJson: String) {
        Timber.v("RtasrLlm: <<< $rawJson")
        try {
            val root = JSONObject(rawJson)

            val msgType = root.optString("msg_type")
            if (msgType == "action" || msgType == "result") {
                handleNewFormat(root, msgType)
                return
            }

            handleOldFormat(root)

        } catch (e: Exception) {
            Timber.e(e, "RtasrLlm: failed to parse message")
            emit(Event.Error("PARSE_ERROR", e.message ?: "Parse error"))
        }
    }

    private fun handleNewFormat(root: JSONObject, msgType: String) {
        if (root.optString("res_type") == "frc") {
            val desc = root.optJSONObject("data")?.optString("desc") ?: "Unknown function error"
            Timber.w("RtasrLlm: function error: $desc")
            emit(Event.Error("FRC_ERROR", desc))
            emit(Event.Stopped)
            return
        }

        val dataObj = root.optJSONObject("data") ?: run {
            Timber.w("RtasrLlm: new format missing data object")
            return
        }

        if (msgType == "action") {
            val action = dataObj.optString("action", "")
            when (action) {
                "started" -> {
                    Timber.d("RtasrLlm: session started")
                    emit(Event.Started)
                }
                "end" -> {
                    val code = dataObj.optString("code", "0")
                    val message = dataObj.optString("message", "Session ended")
                    if (code != "0") {
                        Timber.w("RtasrLlm: end with code=$code message=$message")
                        emit(Event.Error(code, message))
                    } else {
                        Timber.d("RtasrLlm: session ended normally")
                    }
                    emit(Event.Stopped)
                }
                "error" -> {
                    val code = dataObj.optString("code", "UNKNOWN")
                    val desc = dataObj.optString("message", "Unknown error")
                    Timber.w("RtasrLlm: error action code=$code desc=$desc")
                    emit(Event.Error(code, desc))
                    emit(Event.Stopped)
                }
                else -> Timber.d("RtasrLlm: unknown action=$action")
            }
        } else if (msgType == "result") {
            val resType = root.optString("res_type", "asr")
            if (resType == "asr") {
                parseAndEmitResult(dataObj.toString())
            } else {
                Timber.d("RtasrLlm: unknown res_type=$resType")
            }
        }
    }

    private fun handleOldFormat(root: JSONObject) {
        when (val action = root.optString("action")) {
            "started" -> {
                Timber.d("RtasrLlm: session started, sid=${root.optString("sid")}")
                emit(Event.Started)
            }

            "result" -> {
                val code = root.optString("code", "0")
                if (code != "0") {
                    val desc = root.optString("desc", "Error code: $code")
                    Timber.w("RtasrLlm: result error code=$code desc=$desc")
                    emit(Event.Error(code, desc))
                    return
                }

                val dataStr = root.optString("data", "")
                if (dataStr.isNotEmpty()) {
                    parseAndEmitResult(dataStr)
                }
            }

            "error" -> {
                val code = root.optString("code", "UNKNOWN")
                val desc = root.optString("desc", "Unknown error")
                Timber.w("RtasrLlm: error action code=$code desc=$desc")
                emit(Event.Error(code, desc))
                emit(Event.Stopped)
            }

            else -> Timber.d("RtasrLlm: unknown action=$action")
        }
    }

    private fun parseAndEmitResult(dataJson: String) {
        val data = try {
            JSONObject(dataJson)
        } catch (e: Exception) {
            Timber.e(e, "RtasrLlm: data JSON parse failed: $dataJson")
            return
        }

        val segId = data.optInt("seg_id", -1)
        val isLast = data.optBoolean("ls", false)

        if (segId != -1 && !seenSegIds.add(segId)) {
            Timber.d("RtasrLlm: duplicate seg_id=$segId, dropping")
            if (isLast) {
                emit(Event.Stopped)
                webSocket?.close(1000, "session complete")
            }
            return
        }

        val cn = data.optJSONObject("cn") ?: run {
            if (isLast) {
                emit(Event.Stopped)
                webSocket?.close(1000, "session complete")
            }
            return
        }

        val st = cn.optJSONObject("st") ?: return
        val type = st.optString("type", "1")
        val rt = st.optJSONArray("rt") ?: return

        val currentText = buildString {
            for (i in 0 until rt.length()) {
                val ws = rt.getJSONObject(i).optJSONArray("ws") ?: continue
                for (j in 0 until ws.length()) {
                    val wsItem = ws.getJSONObject(j)
                    val cw = wsItem.optJSONArray("cw") ?: continue
                    for (k in 0 until cw.length()) {
                        val cwItem = cw.getJSONObject(k)
                        val wp = cwItem.optString("wp", "n")
                        val w = cwItem.optString("w", "")
                        if (wp != "g" && w.isNotEmpty()) {
                            append(w)
                        }
                    }
                }
            }
        }

        val fullText = committedText.toString() + currentText

        when (type) {
            "0" -> {
                committedText.append(currentText)
                Timber.d("RtasrLlm: Final seg_id=$segId text=\"$currentText\"")
                emit(Event.Final(fullText))
            }
            "1" -> {
                Timber.d("RtasrLlm: Partial seg_id=$segId text=\"$currentText\"")
                emit(Event.Partial(fullText))
            }
            else -> Timber.w("RtasrLlm: unknown type=$type")
        }

        if (isLast) {
            Timber.d("RtasrLlm: session complete (ls=true)")
            emit(Event.Stopped)
            webSocket?.close(1000, "session complete")
        }
    }

    private fun emit(event: Event) {
        if (isCancelled.get()) return
        onEvent?.invoke(event)
    }

    private fun buildAuthUrl(config: RtasrLlmConfig, uuid: String): String? {
        return try {
            val baseHost = "office-api-ast-dx.iflyaisol.com"
            val path = "/ast/communicate/v1"

            val utc = isoDateTime()

            val params = TreeMap<String, String>().apply {
                put("accessKeyId", config.accessKeyId)
                put("appId", config.appId)
                put("audio_encode", config.audioEncode.param)
                put("lang", config.lang.param)
                put("samplerate", config.sampleRate.toString())
                put("utc", utc)
                put("uuid", uuid)
                config.pd?.let { put("pd", it.param) }
                if (config.roleType != RtasrLlmConfig.RoleType.Disabled) {
                    put("role_type", config.roleType.param.toString())
                }
                if (!config.engPunc) put("eng_punc", "0")
                if (config.engVadMdn != RtasrLlmConfig.VadMode.Far) {
                    put("eng_vad_mdn", config.engVadMdn.param.toString())
                }
            }

            val baseString = params.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            val signatureBytes = hmacSha1(baseString, config.accessKeySecret)
            val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
            val signatureEncoded = URLEncoder.encode(signature, "UTF-8")

            val queryString = "$baseString&signature=$signatureEncoded"
            "wss://$baseHost$path?$queryString"
                .also { Timber.d("RtasrLlm: auth URL built, uuid=$uuid") }

        } catch (e: Exception) {
            Timber.e(e, "RtasrLlm: failed to build auth URL")
            null
        }
    }

    private fun isoDateTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return sdf.format(Date())
    }

    private fun hmacSha1(data: String, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
}
