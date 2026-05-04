/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice.engines.doubao

import org.fcitx.fcitx5.android.input.voice.EngineConfig

/*
 * 豆包（ByteDance Volcano Engine SAUC）语音识别配置
 * 新版控制台只需 X-Api-Key + Resource ID
 * 参见 https://www.volcengine.com/docs/6561/1354869
 */
data class DoubaoConfig(
    // X-Api-Key
    val apiKey: String,
    // X-Api-Resource-Id，资源 ID
    val resourceId: String,
    // 语言
    val language: String = "zh-CN",
    // 启用 ITN 文本规范化
    val enableItn: Boolean = true,
    // 启用标点预测
    val enablePunc: Boolean = true,
    // 启用语义顺滑
    val enableDdc: Boolean = false,
    // 输出语音分句信息
    val showUtterances: Boolean = false,
) : EngineConfig
