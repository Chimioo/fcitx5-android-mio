/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

data class IflytekAstConfig(
    val appId: String,
    val apiKey: String,
    val apiSecret: String,
    val lang: String,
    val uuid: String?,
    val pd: String?
)
