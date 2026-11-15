/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Chimioo
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.setPaddingDp
import kotlin.math.ceil
import kotlin.math.max

internal data class CandidatePage(
    val start: Int,
    val end: Int,
    val displayWidths: IntArray,
    val fillToWidth: Boolean
)

/**
 * Measures candidates into non-overlapping horizontal pages that match
 * [HorizontalCandidateMode] sizing rules.
 */
internal class CandidatePagePacker(
    private val paint: Paint,
    private val itemMinWidth: Int,
    private val itemHorizontalPadding: Int,
    private val dividerWidth: Int
) {
    companion object {
        fun createMeasurePaint(sampleTextView: TextView): Paint {
            sampleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            return Paint(sampleTextView.paint)
        }
    }

    fun measureCandidateWidth(candidate: CandidateWord, minWidth: Int, maxWidth: Int): Int {
        val textWidth = ceil(paint.measureText(candidate.text)).toInt()
        val commentWidth = if (candidate.comment.isNotBlank()) {
            val sep = if (candidate.spaceBetweenComment) " " else ""
            ceil(paint.measureText(sep + candidate.comment)).toInt()
        } else {
            0
        }
        // Add 2px safety margin so TextView never wraps or clips compared to Paint measurement.
        val raw = textWidth + commentWidth + itemHorizontalPadding + 2
        return raw.coerceAtLeast(minWidth).coerceAtMost(maxWidth)
    }

    fun pack(
        candidates: List<CandidateWord>,
        fromIndex: Int,
        totalCandidates: Int,
        availableWidth: Int,
        fillStyle: HorizontalCandidateMode,
        maxSpanCount: Int,
        maxPages: Int
    ): List<CandidatePage> {
        if (fromIndex >= candidates.size || availableWidth <= 0 || maxPages <= 0) {
            return emptyList()
        }
        val autoMinWidth = if (fillStyle == AutoFillWidth && maxSpanCount > 0) {
            max(itemMinWidth, availableWidth / maxSpanCount - dividerWidth)
        } else {
            itemMinWidth
        }

        val pages = ArrayList<CandidatePage>()
        var cursor = fromIndex
        while (cursor < candidates.size && pages.size < maxPages) {
            val pageStart = cursor
            val rawWidths = ArrayList<Int>(8)
            var usedWidth = 0
            while (cursor < candidates.size) {
                val w = measureCandidateWidth(candidates[cursor], autoMinWidth, availableWidth)
                val divider = if (rawWidths.isEmpty()) 0 else dividerWidth
                if (rawWidths.isNotEmpty() && usedWidth + divider + w > availableWidth) {
                    break
                }
                usedWidth += divider + w
                rawWidths.add(w)
                cursor++
            }
            val count = rawWidths.size
            val totalKnown = max(totalCandidates, candidates.size)
            val fillToWidth = when (fillStyle) {
                NeverFillWidth -> false
                AlwaysFillWidth -> true
                AutoFillWidth -> cursor < totalKnown || count >= maxSpanCount
            }
            val displayWidths = IntArray(count)
            val spare = (availableWidth - usedWidth).coerceAtLeast(0)
            if (fillToWidth && spare > 0 && count > 0) {
                val extraEach = spare / count
                val rem = spare % count
                for (i in 0 until count) {
                    displayWidths[i] = rawWidths[i] + extraEach + if (i < rem) 1 else 0
                }
            } else {
                for (i in 0 until count) {
                    displayWidths[i] = rawWidths[i]
                }
            }
            pages.add(CandidatePage(pageStart, cursor, displayWidths, fillToWidth))
        }
        return pages
    }
}

internal class HorizontalCandidatePageAdapter(
    private val theme: Theme,
    private val dividerDrawable: Drawable,
    private val onCandidateClick: (index: Int) -> Unit,
    private val onCandidateLongClick: (index: Int, word: String, anchor: android.view.View) -> Unit
) : RecyclerView.Adapter<HorizontalCandidatePageAdapter.PageViewHolder>() {

    class PageViewHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val slots = ArrayList<CandidateItemUi>()
        val boundCandidates = ArrayList<CandidateWord>()
    }

    private var candidates: List<CandidateWord> = emptyList()
    private var pages: List<CandidatePage> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(candidates: List<CandidateWord>, pages: List<CandidatePage>) {
        this.candidates = candidates
        this.pages = pages
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = this@HorizontalCandidatePageAdapter.dividerDrawable
            layoutParams = RecyclerView.LayoutParams(matchParent, matchParent)
        }
        return PageViewHolder(row)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        val count = page.end - page.start
        val row = holder.row
        if (row.childCount > count) {
            row.removeViews(count, row.childCount - count)
        }
        for (i in 0 until count) {
            val globalIdx = page.start + i
            val candidate = candidates[globalIdx]
            val ui = if (i < holder.slots.size) {
                holder.slots[i]
            } else {
                CandidateItemUi(row.context, theme).apply {
                    root.setPaddingDp(10, 0, 10, 0)
                }.also {
                    holder.slots.add(it)
                    holder.boundCandidates.add(CandidateWord.Empty)
                }
            }
            if (ui.root.parent == null) {
                row.addView(ui.root)
            }
            val targetWidth = page.displayWidths[i]
            val lp = ui.root.layoutParams as? LinearLayout.LayoutParams
            if (lp == null) {
                ui.root.layoutParams = LinearLayout.LayoutParams(
                    targetWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            } else if (lp.width != targetWidth || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = targetWidth
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                ui.root.layoutParams = lp
            }
            if (holder.boundCandidates[i] != candidate) {
                holder.boundCandidates[i] = candidate
                ui.updateCandidate(candidate)
            }
            ui.root.setOnClickListener {
                onCandidateClick(globalIdx)
            }
            ui.root.setOnLongClickListener {
                onCandidateLongClick(globalIdx, candidate.text, ui.root)
                true
            }
        }
        resetPageTransform(row)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        for (i in 0 until holder.slots.size) {
            holder.slots[i].root.setOnClickListener(null)
            holder.slots[i].root.setOnLongClickListener(null)
            holder.boundCandidates[i] = CandidateWord.Empty
        }
        resetPageTransform(holder.row)
    }

    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        resetPageTransform(holder.row)
    }

    private fun resetPageTransform(view: LinearLayout) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
    }
}
