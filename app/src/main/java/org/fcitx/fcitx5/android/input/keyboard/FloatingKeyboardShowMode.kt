/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class FloatingKeyboardShowMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Always(R.string.floating_keyboard_show_always),
    Portrait(R.string.floating_keyboard_show_portrait),
    Landscape(R.string.floating_keyboard_show_landscape),
}
