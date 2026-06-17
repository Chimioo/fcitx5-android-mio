/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledDimenPxSize
import splitties.resources.styledDrawable
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageButton
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp
import splitties.views.textAppearance

class DynamicListEntryUi(override val ctx: Context) : Ui {

    val handleImage = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_drag_handle_24)!!.apply {
            setTint(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        }
        setPaddingDp(3, 0, 3, 0)
    }

    val multiselectCheckBox = view(::MaterialCheckBox)

    val checkBox = view(::MaterialCheckBox)

    val nameText = textView {
        textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
    }

    val detailText = textView {
        visibility = View.GONE
        textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceSmall)
        setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
    }

    private val textColumn = verticalLayout {
        setPaddingDp(0, 10, 0, 10)
        add(nameText, ViewGroup.LayoutParams(matchParent, wrapContent))
        add(detailText, ViewGroup.LayoutParams(matchParent, wrapContent))
    }

    val editButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageDrawable = drawable(R.drawable.ic_baseline_edit_24)!!.apply {
            setTint(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        }
    }

    val settingsButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageDrawable = drawable(R.drawable.ic_baseline_settings_24)!!.apply {
            setTint(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        }
    }

    override val root: View = constraintLayout {
        layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
        minHeight = styledDimenPxSize(android.R.attr.listPreferredItemHeightSmall)

        val paddingStart = styledDimenPxSize(android.R.attr.listPreferredItemPaddingStart)

        add(handleImage, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            startOfParent(paddingStart)
        })

        add(multiselectCheckBox, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            after(handleImage, paddingStart)
        })

        add(checkBox, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            after(multiselectCheckBox, paddingStart)
        })

        add(textColumn, lParams {
            width = matchConstraints
            height = wrapContent
            centerVertically()
            after(checkBox, paddingStart)
            before(editButton, dp(12))
        })

        add(editButton, lParams {
            width = dp(60)
            height = matchConstraints
            topOfParent()
            bottomOfParent()
            before(settingsButton)
        })

        add(settingsButton, lParams {
            width = dp(60)
            height = matchConstraints
            topOfParent()
            bottomOfParent()
            endOfParent()
        })
    }
}

