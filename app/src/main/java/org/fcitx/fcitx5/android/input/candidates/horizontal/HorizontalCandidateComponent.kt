/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
// Modified by Chimioo under LGPL-2.1 license

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    companion object {
        private const val PREFETCH_BATCH_SIZE = 48
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val swipePaging by AppPrefs.getInstance().keyboard.horizontalCandidateSwipePaging
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private val loadedCandidates = ArrayList<CandidateWord>()
    private var totalCandidates = -1
    private var pages: List<CandidatePage> = emptyList()
    private var currentPage = 0
    private var updateGeneration = 0L
    private var fetchingMore = false
    private var pendingRebuildOnMeasure = false
    private var lastMeasuredWidth = 0

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last offset
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded(firstPageCount: Int) {
        _expandedCandidateOffset.tryEmit(firstPageCount)
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (adapter.total == firstPageCount || adapter.total <= 0)
        )
    }

    // Preserved as the public state holder referenced by expanded window and key action listener.
    val adapter: HorizontalCandidateViewAdapter by lazy {
        HorizontalCandidateViewAdapter(theme)
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    private val pagePacker by lazy {
        val sampleText = AutoScaleTextView(context)
        CandidatePagePacker(
            paint = CandidatePagePacker.createMeasurePaint(sampleText),
            itemMinWidth = context.dp(40),
            itemHorizontalPadding = context.dp(20),
            dividerWidth = dividerDrawable.intrinsicWidth
        )
    }

    private val pageAdapter by lazy {
        HorizontalCandidatePageAdapter(
            theme = theme,
            dividerDrawable = dividerDrawable,
            onCandidateClick = { index ->
                if (index in 0 until loadedCandidates.size) {
                    fcitx.launchOnReady { it.select(index) }
                }
            },
            onCandidateLongClick = { index, word, anchor ->
                if (index in 0 until loadedCandidates.size) {
                    inputView.showCandidateActionMenu(index, word, anchor)
                }
            }
        )
    }

    val layoutManager: LinearLayoutManager by lazy {
        object : LinearLayoutManager(context, HORIZONTAL, false) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = swipePaging && pages.size > 1
        }
    }

    private val snapHelper = PagerSnapHelper()

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w > 0 && w != lastMeasuredWidth) {
                    lastMeasuredWidth = w
                    if (loadedCandidates.isNotEmpty() || pendingRebuildOnMeasure) {
                        pendingRebuildOnMeasure = false
                        post { rebuildAllPages(resetScroll = false) }
                    }
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = pageAdapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            snapHelper.attachToRecyclerView(this)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    applyPageTransitionTransforms(recyclerView)
                    updateVisiblePageIndex(recyclerView)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        prefetchMoreIfNeeded()
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        resetChildTransforms(recyclerView)
                        updateVisiblePageIndex(recyclerView)
                    }
                }
            })
        }
    }

    private fun applyPageTransitionTransforms(recyclerView: RecyclerView) {
        val pageWidth = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
        if (pageWidth <= 0) return
        if (disableAnimation) {
            resetChildTransforms(recyclerView)
            return
        }
        val parallaxShift = recyclerView.dp(12).toFloat()
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val offset = ((child.left - recyclerView.paddingLeft).toFloat() / pageWidth)
                .coerceIn(-1f, 1f)
            val fraction = abs(offset)
            child.alpha = 1f - 0.35f * fraction
            child.scaleY = 1f - 0.08f * fraction
            child.scaleX = 1f - 0.03f * fraction
            child.translationX = -offset * parallaxShift
        }
    }

    private fun resetChildTransforms(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            child.alpha = 1f
            child.scaleX = 1f
            child.scaleY = 1f
            child.translationX = 0f
        }
    }

    private fun updateVisiblePageIndex(recyclerView: RecyclerView) {
        val snapView = snapHelper.findSnapView(layoutManager)
        val pos = if (snapView != null) {
            recyclerView.getChildAdapterPosition(snapView)
        } else {
            layoutManager.findFirstVisibleItemPosition()
        }
        if (pos != RecyclerView.NO_POSITION && pos != currentPage) {
            currentPage = pos
            prefetchMoreIfNeeded()
        }
    }

    private fun rebuildAllPages(resetScroll: Boolean) {
        if (loadedCandidates.isEmpty()) {
            pages = emptyList()
            pageAdapter.submit(emptyList(), emptyList())
            refreshExpanded(0)
            return
        }
        val availableWidth = (view.width - view.paddingLeft - view.paddingRight)
            .takeIf { it > 0 } ?: lastMeasuredWidth
        if (availableWidth <= 0) {
            pendingRebuildOnMeasure = true
            return
        }
        val allowSwipe = swipePaging
        val maxPages = if (allowSwipe) Int.MAX_VALUE else 1
        pages = pagePacker.pack(
            candidates = loadedCandidates,
            fromIndex = 0,
            totalCandidates = totalCandidates,
            availableWidth = availableWidth,
            fillStyle = fillStyle,
            maxSpanCount = maxSpanCountPref.getValue(),
            maxPages = maxPages
        )
        pageAdapter.submit(loadedCandidates.toList(), pages)
        if (resetScroll) {
            currentPage = 0
            layoutManager.scrollToPositionWithOffset(0, 0)
            resetChildTransforms(view)
        }
        refreshExpanded(pages.firstOrNull()?.end ?: 0)
        if (allowSwipe && loadedCandidates.size < totalCandidates && pages.size <= 2) {
            prefetchMoreIfNeeded()
        }
    }

    private fun repackTailAfterAppend() {
        val availableWidth = (view.width - view.paddingLeft - view.paddingRight)
            .takeIf { it > 0 } ?: lastMeasuredWidth
        if (availableWidth <= 0 || pages.isEmpty()) {
            rebuildAllPages(resetScroll = false)
            return
        }
        // Keep earlier complete pages untouched so the visible page never shifts while scrolling.
        val keepCount = max(0, pages.size - 1)
        val head = pages.subList(0, keepCount)
        val tailStart = head.lastOrNull()?.end ?: 0
        val tail = pagePacker.pack(
            candidates = loadedCandidates,
            fromIndex = tailStart,
            totalCandidates = totalCandidates,
            availableWidth = availableWidth,
            fillStyle = fillStyle,
            maxSpanCount = maxSpanCountPref.getValue(),
            maxPages = Int.MAX_VALUE
        )
        pages = head + tail
        pageAdapter.submit(loadedCandidates.toList(), pages)
    }

    private fun prefetchMoreIfNeeded() {
        if (!swipePaging || fetchingMore) return
        if (loadedCandidates.size >= totalCandidates || totalCandidates <= 0) return
        if (currentPage < pages.size - 2) return

        val gen = updateGeneration
        val offset = loadedCandidates.size
        fetchingMore = true
        fcitx.launchOnReady { service ->
            val extra = service.getCandidates(offset, PREFETCH_BATCH_SIZE)
            view.post {
                if (gen != updateGeneration) return@post
                fetchingMore = false
                if (extra.isNotEmpty() && loadedCandidates.size == offset) {
                    loadedCandidates.addAll(extra)
                    repackTailAfterAppend()
                }
            }
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        val candidates = data.candidates
        val total = data.total
        updateGeneration++
        fetchingMore = false
        currentPage = 0

        loadedCandidates.clear()
        loadedCandidates.addAll(candidates)
        totalCandidates = total
        adapter.updateCandidates(candidates, total)

        pendingRebuildOnMeasure = false
        rebuildAllPages(resetScroll = true)
    }
}
