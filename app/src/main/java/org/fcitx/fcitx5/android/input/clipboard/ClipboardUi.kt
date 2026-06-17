/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.recyclerview.recyclerView
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val viewAnimator =  view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(viewAnimator, defaultLParams(matchParent, matchParent))
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    val addCategoryButton = ToolButton(ctx, R.drawable.ic_baseline_plus_24, theme).apply {
        contentDescription = ctx.getString(R.string.clipboard_add_category)
    }

    private val chipContainer = horizontalLayout {
        gravity = Gravity.CENTER_VERTICAL
    }

    val chipScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(
            chipContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    val extension = constraintLayout {
        // Anchor the chips flush against the title on the left and the toolbar
        // buttons flush against the right edge of the title bar. Using
        // ConstraintLayout here (instead of LinearLayout + weight) is the only
        // way to guarantee the chips fill the middle and the buttons sit on
        // the far right — LinearLayout's weight measure can be unreliable
        // when the parent itself is match_constraints inside another
        // ConstraintLayout, which made the buttons appear glued to the left
        // when the title bar's extension ended up sized to its content.
        add(chipScrollView, lParams(0, dp(40)) {
            startOfParent()
            before(addCategoryButton, dp(4))
            topOfParent()
            bottomOfParent()
        })
        add(addCategoryButton, lParams(dp(40), dp(40)) {
            before(deleteAllButton, dp(0))
            topOfParent()
            bottomOfParent()
        })
        add(deleteAllButton, lParams(dp(40), dp(40)) {
            endOfParent()
            topOfParent()
            bottomOfParent()
        })
    }

    private fun createChipBackground(selected: Boolean): Drawable {
        val radius = ctx.dp(100).toFloat()
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(
                if (selected) theme.genericActiveBackgroundColor
                else theme.keyPressHighlightColor
            )
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(theme.genericActiveBackgroundColor),
            fill,
            mask
        )
    }

    private fun addChip(
        text: String,
        selected: Boolean,
        longClickable: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ) {
        val chip = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(
                if (selected) Color.WHITE
                else theme.keyTextColor
            )
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            background = createChipBackground(selected)
            isClickable = true
            isFocusable = true
            isLongClickable = longClickable && onLongClick != null
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            if (isLongClickable) setOnLongClickListener { onLongClick?.invoke(); true }
        }
        chipContainer.addView(chip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = ctx.dp(6)
            gravity = Gravity.CENTER_VERTICAL
        })
    }

    fun setCategories(
        categories: List<String>,
        selectedCategory: String?,
        onSelect: (String?) -> Unit,
        onLongPressCategory: ((String) -> Unit)? = null
    ) {
        chipContainer.removeAllViews()
        addChip(
            ctx.getString(R.string.clipboard_all_categories),
            selectedCategory == null,
            longClickable = false,
            onClick = { onSelect(null) }
        )
        addChip(
            ctx.getString(R.string.clipboard_uncategorized),
            selectedCategory == "",
            longClickable = false,
            onClick = { onSelect("") }
        )
        categories.forEach { category ->
            addChip(
                category,
                selectedCategory == category,
                longClickable = true,
                onClick = { onSelect(category) },
                onLongClick = { onLongPressCategory?.invoke(category) }
            )
        }
        chipScrollView.post { chipScrollView.scrollTo(0, 0) }
    }

    private fun setExtensionShown(enabled: Boolean) {
        val visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        deleteAllButton.visibility = visibility
        addCategoryButton.visibility = visibility
        chipScrollView.visibility = visibility
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setExtensionShown(true)
            }
            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setExtensionShown(false)
            }
            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setExtensionShown(false)
            }
        }
    }
}