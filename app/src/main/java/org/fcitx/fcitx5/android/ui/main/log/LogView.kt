/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView
import androidx.core.text.buildSpannedString
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Logcat
import splitties.views.bottomPadding
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.verticalLayoutManager

class LogView @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null) :
    HorizontalScrollView(context, attributeSet) {

    private var logcat: Logcat? = null

    private val logAdapter = LogAdapter()

    private val rv = recyclerView {
        adapter = logAdapter
        layoutManager = verticalLayoutManager()
    }

    init {
        add(rv, lParams(wrapContent, matchParent))
    }

    override fun onDetachedFromWindow() {
        logcat?.shutdownLogFlow()
        super.onDetachedFromWindow()
    }

    fun append(content: String) {
        logAdapter.append(buildSpannedString {
            append(content)
        })
    }

    fun setLogcat(logcat: Logcat) {
        this.logcat = logcat
        logcat.initLogFlow()
        logcat.logFlow.onEach {
            logAdapter.append(buildSpannedString {
                append(it)
            })
        }.launchIn(findViewTreeLifecycleOwner()!!.lifecycleScope)
    }

    val currentLog: String
        get() = logAdapter.fullLogString()

    fun clear() {
        logAdapter.clear()
    }

    fun setBottomPadding(padding: Int) {
        rv.clipToPadding = false
        rv.bottomPadding = padding
    }
}

