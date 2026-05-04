/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice.engines.doubao

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * 豆包（ByteDance Volcano Engine SAUC）流式语音识别 WebSocket 客户端
 * 实现自定义二进制帧协议。参考官方 Java Demo
 * 协议文档: https://www.volcengine.com/docs/6561/1631584
 */

class DoubaoWebSocketClient {

    sealed class Event {
        data object Started : Event()
        data class Partial(val text: String) : Event()
        data class Final(val text: String) : Event()
        data class Error(val code: String, val message: String) : Event()
        data object Stopped : Event()
    }


    private companion object {
        const val PROTOCOL_VERSION = 0b0001
        const val DEFAULT_HEADER_SIZE = 0b0001 // 1 x 4 = 4 bytes

        const val CLIENT_FULL_REQUEST = 0b0001
        const val CLIENT_AUDIO_ONLY_REQUEST = 0b0010
        const val SERVER_FULL_RESPONSE = 0b1001
        const val SERVER_ERROR_RESPONSE = 0b1111

        const val NO_SEQUENCE = 0b0000
        const val POS_SEQUENCE = 0b0001
        const val NEG_SEQUENCE = 0b0010
        const val NEG_WITH_SEQUENCE = 0b0011

        const val NO_SERIALIZATION = 0b0000
        const val JSON = 0b0001

        const val GZIP = 0b0001

        fun buildHeader(
            messageType: Int,
            messageTypeSpecificFlags: Int,
            serialMethod: Int,
            compressionType: Int,
            reservedData: Int = 0
        ): ByteArray {
            return byteArrayOf(
                ((PROTOCOL_VERSION shl 4) or DEFAULT_HEADER_SIZE).toByte(),
                ((messageType shl 4) or messageTypeSpecificFlags).toByte(),
                ((serialMethod shl 4) or compressionType).toByte(),
                reservedData.toByte()
            )
        }

        fun intToBytes(value: Int): ByteArray {
            return byteArrayOf(
                ((value shr 24) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
        }

        fun bytesToInt(src: ByteArray): Int {
            require(src.size == 4) { "Expected 4 bytes, got ${src.size}" }
            return ((src[0].toInt() and 0xFF) shl 24) or
                    ((src[1].toInt() and 0xFF) shl 16) or
                    ((src[2].toInt() and 0xFF) shl 8) or
                    (src[3].toInt() and 0xFF)
        }

        fun gzipCompress(src: ByteArray): ByteArray {
            if (src.isEmpty()) return ByteArray(0)
            val out = ByteArrayOutputStream()
            try {
                GZIPOutputStream(out).use { gzip -> gzip.write(src) }
            } catch (e: Exception) {
                Timber.e(e, "Doubao: gzip compress failed")
                return ByteArray(0)
            }
            return out.toByteArray()
        }

        fun gzipDecompress(src: ByteArray): ByteArray {
            if (src.isEmpty()) return ByteArray(0)
            val out = ByteArrayOutputStream()
            try {
                GZIPInputStream(ByteArrayInputStream(src)).use { gzip ->
                    val buf = ByteArray(256)
                    var len: Int
                    while (gzip.read(buf).also { len = it } > 0) {
                        out.write(buf, 0, len)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Doubao: gzip decompress failed")
                return ByteArray(0)
            }
            return out.toByteArray()
        }
    }


    private var webSocket: WebSocket? = null
    private var onEvent: ((Event) -> Unit)? = null
    private val isCancelled = AtomicBoolean(false)
    private val endFrameSent = AtomicBoolean(false)

    private val committedText = StringBuilder()
    private var lastPartialText: String = ""

    fun start(config: DoubaoConfig, onEvent: (Event) -> Unit) {
        reset()
        this.onEvent = onEvent

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val url = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        val connectId = UUID.randomUUID().toString()

        val request = Request.Builder()
            .url(url)
            .header("X-Api-Key", config.apiKey)
            .header("X-Api-Resource-Id", config.resourceId)
            .header("X-Api-Connect-Id", connectId)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Sequence", "-1")
            .build()

        webSocket = client.newWebSocket(request, createListener())

        sendFullClientRequest(config)
    }

    fun sendAudioFrame(pcmData: ByteArray) {
        if (isCancelled.get() || endFrameSent.get()) return
        val ws = webSocket ?: return
        sendAudioSegment(ws, pcmData, isLast = false)
    }

    fun stop() {
        if (isCancelled.get()) return
        if (!endFrameSent.compareAndSet(false, true)) return
        val ws = webSocket ?: return
        sendAudioSegment(ws, ByteArray(0), isLast = true)
        Timber.d("Doubao: end frame sent")
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
        committedText.clear()
        lastPartialText = ""
        onEvent = null
        webSocket?.close(1000, "reset")
        webSocket = null
    }

    private var sequence = 1

    private fun nextSequence(): Int = sequence++

    private fun sendFullClientRequest(config: DoubaoConfig) {
        val ws = webSocket ?: return
        val seq = nextSequence()

        val payload = JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", "fcitx5-android-doubao")
            })
            put("audio", JSONObject().apply {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", 16000)
                put("bits", 16)
                put("channel", 1)
                put("language", config.language)
            })
            put("request", JSONObject().apply {
                put("model_name", "bigmodel")
                put("enable_itn", config.enableItn)
                put("enable_punc", config.enablePunc)
                put("enable_ddc", config.enableDdc)
                put("show_utterances", config.showUtterances)
                put("enable_nonstream", false)
            })
        }

        val payloadStr = payload.toString()
        Timber.d("Doubao: full client request: $payloadStr")

        val payloadBytes = gzipCompress(payloadStr.toByteArray(Charsets.UTF_8))
        val header = buildHeader(CLIENT_FULL_REQUEST, POS_SEQUENCE, JSON, GZIP)
        val seqBytes = intToBytes(seq)
        val sizeBytes = intToBytes(payloadBytes.size)

        val frame = header + seqBytes + sizeBytes + payloadBytes
        ws.send(frame.toByteString())
    }

    private fun sendAudioSegment(ws: WebSocket, pcmData: ByteArray, isLast: Boolean) {
        val seq = if (isLast) -nextSequence() else nextSequence()
        val flags: Int = if (isLast) NEG_WITH_SEQUENCE else POS_SEQUENCE

        val payloadBytes = gzipCompress(pcmData)
        val header = buildHeader(CLIENT_AUDIO_ONLY_REQUEST, flags, JSON, GZIP)
        val seqBytes = intToBytes(seq)
        val sizeBytes = intToBytes(payloadBytes.size)

        val frame = header + seqBytes + sizeBytes + payloadBytes
        ws.send(frame.toByteString())
    }


    private fun createListener() = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isCancelled.get()) return
            val logId = response.header("X-Tt-Logid")
            Timber.d("Doubao: WebSocket opened, X-Tt-Logid=$logId")
            emit(Event.Started)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Timber.w("Doubao: received unexpected text message: $text")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (isCancelled.get()) return
            parseAndHandleResponse(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isCancelled.get()) return
            val msg = t.message ?: "Unknown WebSocket error"
            Timber.e(t, "Doubao: WebSocket failure")
            emit(Event.Error("WS_FAILURE", msg))
            emit(Event.Stopped)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("Doubao: WebSocket closed (code=$code reason=$reason)")
        }
    }


    private fun parseAndHandleResponse(data: ByteArray) {
        if (data.isEmpty()) return

        try {
            var offset = 0

            val protocolVersion = (data[offset].toInt() shr 4) and 0x0f
            val headerSize = data[offset].toInt() and 0x0f
            offset++

            val messageType = (data[offset].toInt() shr 4) and 0x0f
            val flags = data[offset].toInt() and 0x0f
            offset++

            val serialization = (data[offset].toInt() shr 4) and 0x0f
            val compression = data[offset].toInt() and 0x0f
            offset++


            val payloadStart = headerSize * 4
            var payload = data.copyOfRange(payloadStart, data.size)

            var payloadSequence = 0
            var isLastPackage = false
            var eventCode = 0


            if ((flags and 0x01) != 0) {

                payloadSequence = bytesToInt(payload.copyOfRange(0, 4))
                payload = payload.copyOfRange(4, payload.size)
            }
            if ((flags and 0x02) != 0) {
                isLastPackage = true
            }
            if ((flags and 0x04) != 0) {
                eventCode = bytesToInt(payload.copyOfRange(0, 4))
                payload = payload.copyOfRange(4, payload.size)
            }

            var code = 0
            var payloadSize = 0

            when (messageType) {
                SERVER_FULL_RESPONSE -> {
                    payloadSize = bytesToInt(payload.copyOfRange(0, 4))
                    payload = payload.copyOfRange(4, payload.size)
                }
                SERVER_ERROR_RESPONSE -> {
                    code = bytesToInt(payload.copyOfRange(0, 4))
                    payloadSize = bytesToInt(payload.copyOfRange(4, 8))
                    payload = payload.copyOfRange(8, payload.size)
                }
            }


            if (compression == GZIP && payload.isNotEmpty()) {
                payload = gzipDecompress(payload)
            }

            if (serialization == JSON && payload.isNotEmpty()) {
                val responseText = String(payload, Charsets.UTF_8)
                Timber.v("Doubao: <<< $responseText")
                handleResponseJson(responseText, isLastPackage)
            }

            if (isLastPackage) {
                emit(Event.Stopped)
            }

        } catch (e: Exception) {
            Timber.e(e, "Doubao: failed to parse response")
            emit(Event.Error("PARSE_ERROR", e.message ?: "Parse error"))
        }
    }

    private fun handleResponseJson(responseText: String, isLastPackage: Boolean) {
        try {
            val root = JSONObject(responseText)


            val code = root.optInt("code", 0)
            if (code != 0) {
                val message = root.optString("message", "Unknown error")
                Timber.w("Doubao: server error code=$code message=$message")
                emit(Event.Error(code.toString(), message))
                return
            }


            val result = root.optJSONObject("result")
            val text = result?.optString("text", "") ?: ""

            if (text.isEmpty()) return


            if (isLastPackage) {
                committedText.append(text)
                val fullText = committedText.toString()
                Timber.d("Doubao: Final text=\"$text\" full=\"$fullText\"")
                emit(Event.Final(fullText))
            } else {
                lastPartialText = text
                val fullText = committedText.toString() + text
                Timber.d("Doubao: Partial text=\"$text\" full=\"$fullText\"")
                emit(Event.Partial(fullText))
            }

        } catch (e: Exception) {
            Timber.e(e, "Doubao: failed to parse response JSON")
            emit(Event.Error("JSON_PARSE_ERROR", e.message ?: "JSON parse error"))
        }
    }



    private fun emit(event: Event) {
        if (isCancelled.get()) return
        onEvent?.invoke(event)
    }

}
