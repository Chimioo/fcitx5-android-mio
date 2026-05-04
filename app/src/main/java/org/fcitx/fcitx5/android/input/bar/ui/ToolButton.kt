/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.OvalShape
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation
    }

    val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private var pressHighlightColor: Int = 0
    private var outlined: Boolean = false
    private var outlineStrokeColor: Int = 0

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        image.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        setIcon(icon)
        setPressHighlightColor(theme.keyPressHighlightColor)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun setIcon(@DrawableRes icon: Int) {
        image.imageResource = icon
    }

    fun setPressHighlightColor(@ColorInt color: Int) {
        pressHighlightColor = color
        updateBackground()
    }

    fun setOutlined(outlined: Boolean, @ColorInt strokeColor: Int) {
        this.outlined = outlined
        outlineStrokeColor = strokeColor
        updateBackground()
    }

    private fun updateBackground() {
        if (!outlined) {
            background = if (disableAnimation) {
                circlePressHighlightDrawable(pressHighlightColor)
            } else {
                borderlessRippleDrawable(pressHighlightColor, dp(20))
            }
            return
        }

        val strokeWidth = dp(2)
        val border = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(strokeWidth, outlineStrokeColor)
            setColor(Color.TRANSPARENT)
        }

        background = if (disableAnimation) {
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    ShapeDrawable(OvalShape()).apply { paint.color = pressHighlightColor })
                addState(intArrayOf(), border)
            }
        } else {
            RippleDrawable(
                ColorStateList.valueOf(pressHighlightColor),
                border,
                ShapeDrawable(OvalShape()).apply { paint.color = Color.WHITE }
            )
        }
    }
}


