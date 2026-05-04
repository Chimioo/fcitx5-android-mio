/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

sealed class VoiceInputUiState {
    object Idle : VoiceInputUiState()

    object Connecting : VoiceInputUiState()

    object Listening : VoiceInputUiState()

    data class Recognizing(val text: String) : VoiceInputUiState()

    data class Error(val message: String) : VoiceInputUiState()
}
