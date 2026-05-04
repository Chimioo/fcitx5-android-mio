/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

class VoiceInputStatusUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val statusText = textView {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(theme.keyTextColor)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setSingleLine(true)
    }

    private val scrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(statusText, ViewGroup.LayoutParams(wrapContent, matchParent))
    }

    override val root = verticalLayout {
        gravity = Gravity.CENTER_VERTICAL
        add(scrollView, lParams(matchParent, wrapContent))
    }

    fun showConnecting() {
        statusText.text = "正在连接..."
        scrollToEnd()
    }

    fun showListening() {
        statusText.text = ""
    }

    fun showRecognizing(text: String) {
        statusText.text = text
        scrollToEnd()
    }

    fun showError(message: String) {
        statusText.text = message
        scrollToEnd()
    }

    fun clear() {
        statusText.text = ""
    }

    private fun scrollToEnd() {
        scrollView.post {
            scrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT)
        }
    }
}
