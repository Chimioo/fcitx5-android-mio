/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

fun Int.alpha(a: Float) = ColorUtils.setAlphaComponent(this, (a * 0xff).roundToInt())

fun Int.alphaPercent(percent: Int): Int {
    val factor = percent.coerceIn(0, 100) / 100f
    return ColorUtils.setAlphaComponent(this, (Color.alpha(this) * factor).roundToInt())
}
