/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.ui.main.modified

import android.R.attr.defaultValue
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fcitx.fcitx5.android.R

class MaterialSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs), Checkable {

    private var materialSwitch: MaterialSwitch? = null
    private var checked: Boolean = false

    init {
        widgetLayoutResource = R.layout.preference_widget_material_switch
    }

    private fun requestToggle() {
        val newChecked = !isChecked
        if (callChangeListener(newChecked)) {
            isChecked = newChecked
        }
    }

    override fun onClick() {
        requestToggle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        materialSwitch = holder.findViewById(R.id.switchWidget) as? MaterialSwitch
        materialSwitch?.let { switch ->
            switch.isChecked = checked
            switch.isEnabled = isEnabled
            switch.setOnClickListener {
                requestToggle()
            }
        }

        // Long press to reset
        holder.itemView.setOnLongClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(title ?: "Preference")
                .setMessage(R.string.whether_reset_switch_preference)
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.reset) { _, _ ->
                    if (key != null) {
                        preferenceManager.sharedPreferences?.edit()?.remove(key)?.apply()
                    }
                    isChecked = getPersistedBoolean(defaultValue as? Boolean ?: false)
                }
                .show()
            true
        }
    }

    override fun isChecked(): Boolean = checked

    override fun setChecked(checked: Boolean) {
        if (this.checked != checked) {
            this.checked = checked
            materialSwitch?.isChecked = checked
            persistBoolean(checked)
            notifyChanged()
        }
    }

    override fun toggle() {
        isChecked = !checked
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        isChecked = getPersistedBoolean(defaultValue as? Boolean ?: false)
    }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? {
        return a.getBoolean(index, false)
    }

    fun restore() {
        isChecked = defaultValue as? Boolean ?: false
    }
}
