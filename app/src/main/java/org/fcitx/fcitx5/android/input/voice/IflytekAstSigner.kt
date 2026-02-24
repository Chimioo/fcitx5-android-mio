/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IflytekAstSigner {

    fun buildSignedUrl(
        config: IflytekAstConfig,
        utc: String,
        sessionId: String,
        samplerate: Int = 16000,
        audioEncode: String = "pcm_s16le"
    ): String {
        val params = linkedMapOf(
            "accessKeyId" to config.apiKey,
            "appId" to config.appId,
            "uuid" to (config.uuid ?: sessionId),
            "utc" to utc,
            "audio_encode" to audioEncode,
            "lang" to config.lang,
            "samplerate" to samplerate.toString(),
        )
        config.pd?.takeIf { it.isNotBlank() }?.let { params["pd"] = it }

        val baseString = params
            .toSortedMap()
            .entries
            .joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v)}"
            }

        val signature = hmacSha1Base64(config.apiSecret, baseString)
        val query = (params.entries + mapOf("signature" to signature).entries)
            .joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

        return "wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1?$query"
    }

    private fun hmacSha1Base64(secret: String, baseString: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        val bytes = mac.doFinal(baseString.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun urlEncode(s: String): String {
        return URLEncoder.encode(s, "UTF-8")
    }
}
