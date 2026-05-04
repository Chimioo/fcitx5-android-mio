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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞标准版实时语音转写（RTASR）WebSocket 客户端
 *
 * 端点: wss://rtasr.xfyun.cn/v1/ws
 * 鉴权: signa = base64(HmacSHA1(MD5(appId + ts), apiKey))
 * 协议: binary frame 发送音频, text frame {"end": true} 结束
 * 响应: JSON {"action": "started|result|error", "code": "...", "data": "...", "desc": "...", "sid": "..."}
 */
class IflytekStdWebSocketClient {

    sealed class Event {
        data object Started : Event()
        data class Partial(val text: String) : Event()
        data class Final(val text: String) : Event()
        data class Error(val code: String, val message: String) : Event()
        data object Stopped : Event()
    }

    private var webSocket: WebSocket? = null
    private var onEvent: ((Event) -> Unit)? = null
    private val seenSegIds = mutableSetOf<Int>()
    private val committedText = StringBuilder()
    private val isCancelled = AtomicBoolean(false)
    private val endFrameSent = AtomicBoolean(false)

    fun start(config: IflytekStdConfig, onEvent: (Event) -> Unit) {
        reset()
        this.onEvent = onEvent

        val url = buildAuthUrl(config) ?: run {
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
        }.toString()

        ws.send(endJson)
        Timber.d("IflytekStd: end frame sent")
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
        onEvent = null
        webSocket?.close(1000, "reset")
        webSocket = null
    }

    private fun createListener() = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (isCancelled.get()) return
            Timber.d("IflytekStd: WebSocket opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isCancelled.get()) return
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Timber.w("IflytekStd: received unexpected binary message")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (isCancelled.get()) return
            val msg = t.message ?: "Unknown WebSocket error"
            Timber.e(t, "IflytekStd: WebSocket failure")
            emit(Event.Error("WS_FAILURE", msg))
            emit(Event.Stopped)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d("IflytekStd: WebSocket closed (code=$code reason=$reason)")
        }
    }

    private fun handleMessage(rawJson: String) {
        Timber.v("IflytekStd: <<< $rawJson")
        try {
            val root = JSONObject(rawJson)
            val action = root.optString("action", "")

            when (action) {
                "started" -> {
                    Timber.d("IflytekStd: session started, sid=${root.optString("sid")}")
                    emit(Event.Started)
                }

                "result" -> {
                    val code = root.optString("code", "0")
                    if (code != "0") {
                        val desc = root.optString("desc", "Error code: $code")
                        Timber.w("IflytekStd: result error code=$code desc=$desc")
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
                    Timber.w("IflytekStd: error action code=$code desc=$desc")
                    emit(Event.Error(code, desc))
                    emit(Event.Stopped)
                }

                else -> Timber.d("IflytekStd: unknown action=$action")
            }
        } catch (e: Exception) {
            Timber.e(e, "IflytekStd: failed to parse message")
            emit(Event.Error("PARSE_ERROR", e.message ?: "Parse error"))
        }
    }

    /**
     * 解析转写结果 data JSON 字符串
     *
     * data 格式:
     * {
     *   "cn": { "st": { "bg": "820", "ed": "0", "rt": [{
     *     "ws": [{ "cw": [{ "w": "啊", "wp": "n" }], "wb": 0, "we": 0 }]
     *   }], "type": "1" } },
     *   "seg_id": 5
     * }
     *
     * type: "0" = 最终结果, "1" = 中间结果
     */
    private fun parseAndEmitResult(dataJson: String) {
        val data = try {
            JSONObject(dataJson)
        } catch (e: Exception) {
            Timber.e(e, "IflytekStd: data JSON parse failed: $dataJson")
            return
        }

        val segId = data.optInt("seg_id", -1)

        if (segId != -1 && !seenSegIds.add(segId)) {
            Timber.d("IflytekStd: duplicate seg_id=$segId, dropping")
            return
        }

        val cn = data.optJSONObject("cn") ?: return

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
                        // wp="g" 为语气词占位或无效词，跳过；否则拼接
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
                Timber.d("IflytekStd: Final seg_id=$segId text=\"$currentText\"")
                emit(Event.Final(fullText))
            }
            "1" -> {
                Timber.d("IflytekStd: Partial seg_id=$segId text=\"$currentText\"")
                emit(Event.Partial(fullText))
            }
            else -> Timber.w("IflytekStd: unknown type=$type")
        }
    }

    private fun emit(event: Event) {
        if (isCancelled.get()) return
        onEvent?.invoke(event)
    }

    /**
     * 构建鉴权 URL
     *
     * 步骤:
     * 1. ts = Unix 秒级时间戳
     * 2. baseString = appId + ts
     * 3. md5 = MD5(baseString)
     * 4. signa = base64(HmacSHA1(md5, apiKey))
     * 5. URL: wss://rtasr.xfyun.cn/v1/ws?appid={appId}&ts={ts}&signa={signa}&lang={lang}...
     */
    private fun buildAuthUrl(config: IflytekStdConfig): String? {
        return try {
            val ts = (System.currentTimeMillis() / 1000).toString()

            val baseString = config.appId + ts
            val md5 = md5(baseString)
            val signaBytes = hmacSha1(md5, config.apiKey)
            val signa = Base64.encodeToString(signaBytes, Base64.NO_WRAP)

            val params = mutableListOf<Pair<String, String>>()
            params.add("appid" to config.appId)
            params.add("ts" to ts)
            params.add("signa" to signa)
            params.add("lang" to config.lang)
            if (!config.punc) {
                params.add("punc" to "0")
            }
            config.pd?.let { params.add("pd" to it) }
            if (config.vadMdn != 1) {
                params.add("vadMdn" to config.vadMdn.toString())
            }
            if (config.roleType != 1) {
                params.add("roleType" to config.roleType.toString())
            }
            if (config.engLangType != 1) {
                params.add("engLangType" to config.engLangType.toString())
            }

            val queryString = params.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            "wss://rtasr.xfyun.cn/v1/ws?$queryString"
                .also { Timber.d("IflytekStd: auth URL built") }

        } catch (e: Exception) {
            Timber.e(e, "IflytekStd: failed to build auth URL")
            null
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha1(data: String, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
}
