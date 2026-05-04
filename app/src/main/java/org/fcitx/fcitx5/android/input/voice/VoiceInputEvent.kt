/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice

sealed class VoiceInputEvent {
    data object Started : VoiceInputEvent()
    data class Partial(val text: String) : VoiceInputEvent()
    data class Final(val text: String) : VoiceInputEvent()
    data class Error(val message: String) : VoiceInputEvent()
    data object Stopped : VoiceInputEvent()
}
