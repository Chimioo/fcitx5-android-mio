/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
// Modified by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.preference.DialogPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.setOnChangeListener
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.wrapContent
import splitties.views.textAppearance

class TwinSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : DialogPreference(context, attrs) {

    var min: Int = 0
    var max: Int = 100
    var step: Int = 1
    var unit: String = ""

    var label: String = ""
    var secondaryKey: String = ""
    var secondaryLabel: String = ""

    var default: Int = 0
    var secondaryDefault: Int = 0
    var defaultLabel: String? = null

    var value = 0
        private set
    var secondaryValue = 0
        private set

    override fun onSetInitialValue(defaultValue: Any?) {
        preferenceDataStore?.apply {
            value = getInt(key, default)
            secondaryValue = getInt(secondaryKey, secondaryDefault)
        } ?: sharedPreferences?.apply {
            value = getInt(key, default)
            secondaryValue = getInt(secondaryKey, secondaryDefault)
        }
    }

    /**
     * @param defaultValue should be `Pair<Int, Int>`
     */
    override fun setDefaultValue(defaultValue: Any?) {
        super.setDefaultValue(defaultValue)
        val (first, second) = defaultValue as? Pair<*, *> ?: return
        default = first as? Int ?: 0
        secondaryDefault = second as? Int ?: 0
    }

    private fun persistValues(primary: Int, secondary: Int) {
        if (!shouldPersist()) return
        value = primary
        secondaryValue = secondary
        preferenceDataStore?.apply {
            putInt(key, primary)
            putInt(secondaryKey, secondary)
        } ?: sharedPreferences?.edit {
            putInt(key, primary)
            putInt(secondaryKey, secondary)
        }
    }

    override fun onClick() {
        showDialog()
    }

    private fun ConstraintLayout.addSlider(
        label: String,
        initialValue: Int,
        defaultValue: Int? = null,
        belowView: View? = null
    ): Slider {
        val textLabel = textView {
            text = label
            textAppearance = context.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        }
        val valueLabel = textView {
            text = textForValue(initialValue, defaultValue)
            textAppearance = context.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        }
        val slider = Slider(context).apply {
            valueFrom = min.toFloat()
            valueTo = max.toFloat()
            stepSize = step.toFloat()
            value = initialValue.toFloat()
            addOnChangeListener { _, value, _ ->
                valueLabel.text = textForValue(value.toInt(), defaultValue)
            }
        }
        val textMargin = dp(24)
        val sliderMargin = dp(10)
        add(textLabel, lParams(wrapContent, wrapContent) {
            if (belowView == null) topOfParent(textMargin)
            else below(belowView, textMargin)
            startOfParent(textMargin)
        })
        add(valueLabel, lParams(wrapContent, wrapContent) {
            if (belowView == null) topOfParent(textMargin)
            else below(belowView, textMargin)
            endOfParent(textMargin)
        })
        add(slider, lParams(matchConstraints, wrapContent) {
            below(valueLabel, sliderMargin)
            centerHorizontally(sliderMargin)
        })
        return slider
    }

    private fun showDialog() {
        var messageText: TextView? = null
        val primarySlider: Slider
        val secondarySlider: Slider
        val dialogContent = context.constraintLayout {
            if (dialogMessage != null) {
                messageText = textView { text = dialogMessage }
                add(messageText, lParams {
                    verticalMargin = dp(8)
                    horizontalMargin = dp(24)
                })
            }
            primarySlider = addSlider(label, value, default, messageText)
            secondarySlider = addSlider(secondaryLabel, secondaryValue, secondaryDefault, primarySlider)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(this@TwinSeekBarPreference.dialogTitle)
            .setView(dialogContent)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val primary = primarySlider.value.toInt()
                val secondary = secondarySlider.value.toInt()
                setValue(primary, secondary)
            }
            .setNeutralButton(R.string.default_) { _, _ ->
                setValue(default, secondaryDefault)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setValue(primary: Int, secondary: Int) {
        if (callChangeListener(primary to secondary)) {
            persistValues(primary, secondary)
            notifyChanged()
        }
    }

    private fun progressForValue(value: Int) = (value - min) / step

    private fun valueForProgress(progress: Int) = (progress * step) + min

    private fun textForValue(value: Int, default: Int? = null): String =
        if (value == default && defaultLabel != null) defaultLabel!! else "$value $unit"

    object SimpleSummaryProvider : SummaryProvider<TwinSeekBarPreference> {
        override fun provideSummary(preference: TwinSeekBarPreference): CharSequence {
            return preference.run {
                val primary = textForValue(value, default)
                val secondary = textForValue(secondaryValue, secondaryDefault)
                "$primary / $secondary"
            }
        }
    }

}


