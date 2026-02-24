/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

import org.json.JSONObject

object IflytekAstResultParser {

    data class ParseResult(
        val text: String,
        val isFinal: Boolean
    )

    fun parse(message: String): ParseResult? {
        val root = try {
            JSONObject(message)
        } catch (_: Exception) {
            return null
        }

        val msgType = root.optString("msg_type")
        if (msgType != "result") return null

        val resType = root.optString("res_type")
        if (resType != "asr") return null

        val data = root.optJSONObject("data") ?: return null
        val isFinal = data.optBoolean("ls", false)

        val cn = data.optJSONObject("cn") ?: return ParseResult("", isFinal)
        val st = cn.optJSONObject("st") ?: return ParseResult("", isFinal)
        val rt = st.optJSONArray("rt") ?: return ParseResult("", isFinal)

        val sb = StringBuilder()
        for (i in 0 until rt.length()) {
            val rtItem = rt.optJSONObject(i) ?: continue
            val ws = rtItem.optJSONArray("ws") ?: continue
            for (j in 0 until ws.length()) {
                val wsItem = ws.optJSONObject(j) ?: continue
                val cw = wsItem.optJSONArray("cw") ?: continue
                // choose first candidate
                val cw0 = cw.optJSONObject(0) ?: continue
                val w = cw0.optString("w")
                sb.append(w)
            }
        }
        return ParseResult(sb.toString(), isFinal)
    }

    fun parseError(message: String): String? {
        val root = try {
            JSONObject(message)
        } catch (_: Exception) {
            return null
        }
        val msgType = root.optString("msg_type")
        if (msgType != "result") return null
        val resType = root.optString("res_type")
        if (resType != "frc") return null
        val data = root.optJSONObject("data") ?: return null
        return data.optString("desc").ifBlank { null }
    }
}
