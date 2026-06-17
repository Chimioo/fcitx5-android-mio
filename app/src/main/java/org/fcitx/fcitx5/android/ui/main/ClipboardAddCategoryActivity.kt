/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.databinding.ActivityClipboardAddCategoryBinding
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.str

class ClipboardAddCategoryActivity : Activity() {

    private val scope: CoroutineScope = MainScope()

    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityClipboardAddCategoryBinding.inflate(layoutInflater).apply {
            editText = clipboardAddCategoryText
            clipboardAddCategoryCancel.setOnClickListener { finish() }
            clipboardAddCategoryOk.setOnClickListener { finishEditing() }
        }
        setContentView(binding.root)
        inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun finishEditing() {
        val newCategory = editText.str.trim()
        if (newCategory.isNotEmpty()) {
            scope.launch { ClipboardManager.addCategory(newCategory) }
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
