/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice.engines.iflytek

import org.fcitx.fcitx5.android.input.voice.EngineConfig

/**
 * 讯飞标准版实时语音转写（RTASR）配置
 *
 * https://www.xfyun.cn/doc/asr/rtasr/API.html
 */
data class IflytekStdConfig(
    val appId: String,
    val apiKey: String,
    val lang: String = "cn",
    val punc: Boolean = true,
    val pd: String? = null,
    val vadMdn: Int = 1,
    val roleType: Int = 1,
    val engLangType: Int = 1,
) : EngineConfig
