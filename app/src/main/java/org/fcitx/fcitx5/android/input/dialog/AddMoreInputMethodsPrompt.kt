/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dialog

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.AppUtil

object AddMoreInputMethodsPrompt {
    fun build(context: Context): AlertDialog {
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.no_more_input_methods)
            .setMessage(R.string.add_more_input_methods)
            .setPositiveButton(R.string.add) { _, _ ->
                AppUtil.launchMainToInputMethodList(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}


