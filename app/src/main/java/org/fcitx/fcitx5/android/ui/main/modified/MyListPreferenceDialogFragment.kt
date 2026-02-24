/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.modified

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.R

class MyListPreferenceDialogFragment : ListPreferenceDialogFragmentCompat() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val p = preference
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.dialogTitle)
            .setIcon(p.dialogIcon)
            .setPositiveButton(p.positiveButtonText, this)
            .setNegativeButton(p.negativeButtonText, this)

        val contentView = onCreateDialogView(requireContext())
        if (contentView != null) {
            onBindDialogView(contentView)
            builder.setView(contentView)
        } else {
            builder.setMessage(p.dialogMessage)
        }

        onPrepareDialogBuilder(builder)
        return builder.create()
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val p = preference as ListPreference
        builder.setNeutralButton(R.string.default_) { _, _ ->
            p.restore()
        }
        super.onPrepareDialogBuilder(builder)
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        fixDialogMargin(view)
    }

    companion object {
        fun newInstance(key: String): MyListPreferenceDialogFragment {
            val fragment = MyListPreferenceDialogFragment()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }
    }
}

