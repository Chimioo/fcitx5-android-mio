/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.editing.TextEditingButton
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.FloatingKeyboardShowMode
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber
import java.util.WeakHashMap
import kotlin.math.min


@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val keyboardBlurRadius by ThemeManager.prefs.keyboardBlurRadius
    private val keyboardOpacity by ThemeManager.prefs.keyboardOpacity

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private fun enableHiddenApiBypassForBlur(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        if (hiddenApiBypassForBlurEnabled) return true
        return runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }.onSuccess {
            hiddenApiBypassForBlurEnabled = it
            Timber.i("Frosted blur hidden API bypass enabled=$it")
        }.onFailure {
            Timber.w(it, "Frosted blur hidden API bypass failed")
        }.getOrDefault(false)
    }

    private fun setBackgroundBlurCornerRadius(drawable: Drawable?, radius: Float) {
        drawable ?: return
        fun invokeCornerRadius(vararg parameterTypes: Class<*>): Boolean {
            val args = FloatArray(parameterTypes.size) { radius }.toTypedArray()
            val method = runCatching {
                drawable.javaClass.getMethod("setCornerRadius", *parameterTypes)
            }.getOrElse {
                drawable.javaClass.getDeclaredMethod("setCornerRadius", *parameterTypes)
                    .apply { isAccessible = true }
            }
            method.invoke(drawable, *args)
            return true
        }
        runCatching {
            invokeCornerRadius(java.lang.Float.TYPE)
        }.recoverCatching {
            invokeCornerRadius(
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE
            )
        }.onFailure {
            Timber.w(it, "Frosted blur drawable corner radius update failed")
        }
    }

    private fun keyboardBackgroundCornerRadius(): Float =
        if (floatingKeyboardLayoutApplied) floatingKeyboardCornerRadiusPx.toFloat() else 0f

    private fun updateKeyboardBackgroundClip() {
        if (floatingKeyboardLayoutApplied) {
            customBackground.outlineProvider = floatingKeyboardOutlineProvider
            customBackground.clipToOutline = true
            setBackgroundBlurCornerRadius(customBackground.background, keyboardBackgroundCornerRadius())
        } else {
            customBackground.clipToOutline = false
            customBackground.outlineProvider = ViewOutlineProvider.BACKGROUND
            setBackgroundBlurCornerRadius(customBackground.background, 0f)
        }
    }

    private fun createBackgroundBlurDrawable(view: View, blurRadius: Int): Drawable? {
        if (!enableHiddenApiBypassForBlur()) return null
        return runCatching {
            val viewRootImpl = runCatching {
                View::class.java.getMethod("getViewRootImpl").invoke(view)
            }.getOrNull() ?: view.rootView.parent
            requireNotNull(viewRootImpl) { "ViewRootImpl is null" }
            val blurDrawable = viewRootImpl.javaClass
                .getDeclaredMethod("createBackgroundBlurDrawable")
                .apply { isAccessible = true }
                .invoke(viewRootImpl) as? Drawable
            requireNotNull(blurDrawable) { "createBackgroundBlurDrawable returned null" }
            blurDrawable.apply {
                javaClass.getMethod("setBlurRadius", Integer.TYPE).invoke(this, blurRadius)
                javaClass.getMethod("setColor", Integer.TYPE).invoke(this, Color.TRANSPARENT)
            }
            setBackgroundBlurCornerRadius(blurDrawable, keyboardBackgroundCornerRadius())
            Timber.i(
                "Frosted blur drawable created: root=%s drawable=%s radius=%d",
                viewRootImpl.javaClass.name,
                blurDrawable.javaClass.name,
                blurRadius
            )
            blurDrawable
        }.onFailure {
            Timber.w(it, "Frosted blur drawable creation failed")
        }.getOrNull()
    }

    private fun applyKeyboardBackground() {
        val blurRadius = keyboardBlurRadius.coerceAtLeast(0)
        val backgroundAlpha = ((100 - keyboardOpacity.coerceIn(0, 100)) * 255 / 100)
        customBackground.background = null
        if (blurRadius == 0) {
            customBackground.scaleType = ImageView.ScaleType.CENTER_CROP
            customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)
            customBackground.imageAlpha = backgroundAlpha
            updateKeyboardBackgroundClip()
            return
        }

        customBackground.scaleType = ImageView.ScaleType.FIT_XY
        customBackground.imageAlpha = 255
        customBackground.setImageBitmap(null)

        fun applyFrostedLayer() {
            val width = customBackground.width.takeIf { it > 0 } ?: keyboardView.width
            val height = customBackground.height.takeIf { it > 0 } ?: keyboardView.height
            if (width <= 0 || height <= 0) return

            customBackground.background = createBackgroundBlurDrawable(this, blurRadius)
            customBackground.setImageBitmap(createFrostedTintBitmap(width, height, blurRadius))
            updateKeyboardBackgroundClip()
        }

        if (customBackground.width > 0 && customBackground.height > 0) {
            applyFrostedLayer()
        } else {
            customBackground.addOnLayoutChangeListener(object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    customBackground.removeOnLayoutChangeListener(this)
                    applyFrostedLayer()
                }
            })
        }
    }

    private fun createFrostedTintBitmap(width: Int, height: Int, blurRadius: Int): Bitmap {
        val alpha = keyboardTintAlpha(blurRadius)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val (topColor, bottomColor) = if (theme.isDark) {
            Color.argb(alpha, 30, 35, 50) to Color.argb(alpha, 20, 25, 40)
        } else {
            Color.argb(alpha, 245, 248, 255) to Color.argb(alpha, 225, 230, 245)
        }
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        ).apply {
            setBounds(0, 0, width, height)
            draw(canvas)
        }
        return bitmap
    }

    private fun keyboardTintAlpha(blurRadius: Int): Int {
        val transparency = keyboardOpacity.coerceIn(0, 100)
        if (blurRadius > 0 && transparency == 0) {
            return 60
        }
        return ((100 - transparency) * 255 / 100).coerceIn(0, 255)
    }

    private companion object {
        var hiddenApiBypassForBlurEnabled = false
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val floatingCornerArcPaint: Paint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(4).toFloat()
            color = ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, 0xCC)
        }
    }

    private fun applyFloatingContentScale() {
        if (!floatingKeyboardLayoutApplied) return
        ensureFloatingContentRegistered()
        val factor = min(floatingKeyboardScaleX, floatingKeyboardScaleY).coerceIn(0.6f, 1.3f)
        if (!lastAppliedFloatingContentScale.isNaN() && kotlin.math.abs(factor - lastAppliedFloatingContentScale) < 0.005f) {
            return
        }
        lastAppliedFloatingContentScale = factor

        val spacingFactor = (factor * factor).coerceIn(0.15f, 1.0f)
        floatingKeyViews.keys.forEach { kv ->
            kv.setMarginScale(spacingFactor)
        }

        floatingBaseTextSizePx.forEach { (tv, base) ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * factor)
        }
        floatingBaseImageScale.forEach { (iv, base) ->
            val parent = iv.parent
            val iconFactor = when (parent) {
                is ToolButton, is TextEditingButton -> factor.coerceAtLeast(0.85f)
                else -> factor
            }
            iv.scaleX = base.first * iconFactor
            iv.scaleY = base.second * iconFactor
        }

        updateFloatingPreeditPosition()
    }

    private fun configurePreeditViewForFloating() {
        preedit.ui.root.updateLayoutParams<LayoutParams> {
            // In floating mode, preedit should follow keyboardView's x/y.
            // Constraint position should start from parent (0,0) so translation is correct.
            startToStart = LayoutParams.PARENT_ID
            endToEnd = unset
            topToTop = LayoutParams.PARENT_ID
            bottomToBottom = unset
            bottomToTop = unset
        }
        preedit.ui.root.bringToFront()
        updateFloatingPreeditPosition()
    }

    private fun restorePreeditViewForNonFloating() {
        preedit.ui.root.translationX = 0f
        preedit.ui.root.translationY = 0f
        preedit.ui.root.updateLayoutParams<LayoutParams> {
            width = LayoutParams.MATCH_PARENT
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topToTop = unset
            bottomToBottom = unset
            bottomToTop = keyboardView.id
        }
    }

    private fun updateFloatingPreeditPosition() {
        if (!floatingKeyboardLayoutApplied) return
        val w = keyboardView.width
        if (w > 0) {
            preedit.ui.root.updateLayoutParams<LayoutParams> {
                width = w
            }
        }
        preedit.ui.root.translationX = keyboardView.x

        val preeditH = preedit.ui.root.height
        val desiredY = if (preeditH > 0) keyboardView.y - preeditH else keyboardView.y
        preedit.ui.root.translationY = desiredY
        preedit.ui.root.bringToFront()
    }

    private val preeditLayoutChangeListener = OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (!floatingKeyboardLayoutApplied) return@OnLayoutChangeListener
        val w = right - left
        val h = bottom - top
        val oldW = oldRight - oldLeft
        val oldH = oldBottom - oldTop
        if (w != oldW || h != oldH) {
            updateFloatingPreeditPosition()
        }
    }

    private fun cornerArcDrawable(startAngle: Float): Drawable {
        val paint = floatingCornerArcPaint
        return object : Drawable() {
            override fun draw(canvas: android.graphics.Canvas) {
                val r = bounds
                val inset = paint.strokeWidth / 2f
                val left = r.left + inset
                val top = r.top + inset
                val right = r.right - inset
                val bottom = r.bottom - inset
                canvas.drawArc(left, top, right, bottom, startAngle, 90f, false, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
                invalidateSelf()
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
                invalidateSelf()
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private val floatingHandleBar = view(::View) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x66FFFFFF)
            cornerRadius = dp(999).toFloat()
        }
    }

    private val floatingHandleContainer = frameLayout {
        setBackgroundColor(Color.TRANSPARENT)
        add(floatingHandleBar, FrameLayout.LayoutParams(dp(72), dp(5)).apply {
            gravity = Gravity.CENTER
        })
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private val floatingExtraSidePaddingPx = dp(8)

    private val floatingKeyboardCornerRadiusPx = dp(18)

    private val floatingCornerHandleSizePx = dp(50)

    private val floatingKeyboardOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(
                0,
                0,
                view.width,
                view.height,
                floatingKeyboardCornerRadiusPx.toFloat()
            )
        }
    }

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val floatingKeyboardEnabled by keyboardPrefs.floatingKeyboard
    private val floatingKeyboardShowModePref = keyboardPrefs.floatingKeyboardShowMode

    private val internalPrefs = AppPrefs.getInstance().internal

    private var floatingKeyboardXPortrait by internalPrefs.floatingKeyboardXPortrait
    private var floatingKeyboardYPortrait by internalPrefs.floatingKeyboardYPortrait
    private var floatingKeyboardScalePortrait by internalPrefs.floatingKeyboardScalePortrait
    private var floatingKeyboardScaleXPortrait by internalPrefs.floatingKeyboardScaleXPortrait
    private var floatingKeyboardScaleYPortrait by internalPrefs.floatingKeyboardScaleYPortrait

    private var floatingKeyboardXLandscape by internalPrefs.floatingKeyboardXLandscape
    private var floatingKeyboardYLandscape by internalPrefs.floatingKeyboardYLandscape
    private var floatingKeyboardScaleLandscape by internalPrefs.floatingKeyboardScaleLandscape
    private var floatingKeyboardScaleXLandscape by internalPrefs.floatingKeyboardScaleXLandscape
    private var floatingKeyboardScaleYLandscape by internalPrefs.floatingKeyboardScaleYLandscape

    // ==================== 悬浮键盘判定逻辑 ====================

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    val isFloatingKeyboardActive: Boolean
        get() {
            if (!floatingKeyboardEnabled) return false
            return when (floatingKeyboardShowModePref.getValue()) {
                FloatingKeyboardShowMode.Always -> true
                FloatingKeyboardShowMode.Portrait -> !isLandscape()
                FloatingKeyboardShowMode.Landscape -> isLandscape()
            }
        }

    // 悬浮键盘布局状态

    private var floatingKeyboardLayoutApplied: Boolean = false

    val isFloatingKeyboardLayoutApplied: Boolean
        get() = floatingKeyboardLayoutApplied

    //  悬浮键盘位置/缩放（按方向存储）

    private var floatingKeyboardX: Int
        get() = if (isLandscape()) floatingKeyboardXLandscape else floatingKeyboardXPortrait
        set(value) {
            if (isLandscape()) floatingKeyboardXLandscape = value else floatingKeyboardXPortrait =
                value
        }

    private var floatingKeyboardY: Int
        get() = if (isLandscape()) floatingKeyboardYLandscape else floatingKeyboardYPortrait
        set(value) {
            if (isLandscape()) floatingKeyboardYLandscape = value else floatingKeyboardYPortrait =
                value
        }

    private var floatingKeyboardScale: Float
        get() = if (isLandscape()) floatingKeyboardScaleLandscape else floatingKeyboardScalePortrait
        set(value) {
            if (isLandscape()) floatingKeyboardScaleLandscape =
                value else floatingKeyboardScalePortrait = value
        }

    private var floatingKeyboardScaleX: Float
        get() = if (isLandscape()) floatingKeyboardScaleXLandscape else floatingKeyboardScaleXPortrait
        set(value) {
            if (isLandscape()) floatingKeyboardScaleXLandscape =
                value else floatingKeyboardScaleXPortrait = value
        }

    private var floatingKeyboardScaleY: Float
        get() = if (isLandscape()) floatingKeyboardScaleYLandscape else floatingKeyboardScaleYPortrait
        set(value) {
            if (isLandscape()) floatingKeyboardScaleYLandscape =
                value else floatingKeyboardScaleYPortrait = value
        }

    private var floatingResizeMode = false

    private val floatingBaseTextSizePx = WeakHashMap<TextView, Float>()
    private val floatingBaseImageScale = WeakHashMap<ImageView, Pair<Float, Float>>()
    private val floatingKeyViews = WeakHashMap<KeyView, Unit>()
    private var floatingContentRegistered = false
    private var floatingContentScalePosted = false
    private var lastAppliedFloatingContentScale = Float.NaN

    private data class FloatingCornerResizeState(
        val startRawX: Float,
        val startRawY: Float,
        val startFactorX: Float,
        val startFactorY: Float,
        val baseW: Float,
        val baseAreaH: Float,
        val basePadH: Float,
        val constantH: Float,
        val fixedX: Float,
        val fixedY: Float,
    )

    private fun setFloatingResizeMode(enabled: Boolean) {
        floatingResizeMode = enabled
        val v = if (enabled) VISIBLE else GONE
        floatingCornerTL.visibility = v
        floatingCornerTR.visibility = v
        floatingCornerBL.visibility = v
        floatingCornerBR.visibility = v
    }

    private fun updateFloatingUiVisibility() {
        if (!isFloatingKeyboardActive) {
            floatingHandleContainer.visibility = GONE
            floatingHandleBar.visibility = GONE
            setFloatingResizeMode(false)
        } else {
            floatingHandleContainer.visibility = VISIBLE
            floatingHandleBar.visibility = VISIBLE
        }
    }

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            return resources.displayMetrics.heightPixels * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
        if (
            key == floatingKeyboardShowModePref.key ||
            key == keyboardPrefs.floatingKeyboard.key ||
            key == keyboardPrefs.floatingKeyboardHideOnFocusLoss.key ||
            key == internalPrefs.floatingKeyboardXPortrait.key ||
            key == internalPrefs.floatingKeyboardYPortrait.key ||
            key == internalPrefs.floatingKeyboardScalePortrait.key ||
            key == internalPrefs.floatingKeyboardScaleXPortrait.key ||
            key == internalPrefs.floatingKeyboardScaleYPortrait.key ||
            key == internalPrefs.floatingKeyboardXLandscape.key ||
            key == internalPrefs.floatingKeyboardYLandscape.key ||
            key == internalPrefs.floatingKeyboardScaleLandscape.key ||
            key == internalPrefs.floatingKeyboardScaleXLandscape.key ||
            key == internalPrefs.floatingKeyboardScaleYLandscape.key
        ) {
            refreshFloatingKeyboardMode()
        }
    }

    val keyboardView: View

    private val floatingCornerTL = view(::View) { background = cornerArcDrawable(180f) }
    private val floatingCornerTR = view(::View) { background = cornerArcDrawable(270f) }
    private val floatingCornerBL = view(::View) { background = cornerArcDrawable(90f) }
    private val floatingCornerBR = view(::View) { background = cornerArcDrawable(0f) }

    private val floatingCornerOverlay = frameLayout {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        applyKeyboardBackground()

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams(matchConstraints, matchConstraints) {
                topOfParent()
                bottomOfParent()
                startOfParent()
                endOfParent()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })

            add(floatingHandleContainer, lParams(matchParent, dp(24)) {
                bottomOfParent()
                centerHorizontally()
            })
        }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })

        preedit.ui.root.addOnLayoutChangeListener(preeditLayoutChangeListener)

        add(floatingCornerOverlay, lParams(matchParent, matchParent) {
            topOfParent()
            bottomOfParent()
            startOfParent()
            endOfParent()
        })

        run {
            val cornerSize = floatingCornerHandleSizePx
            floatingCornerOverlay.add(
                floatingCornerTL,
                FrameLayout.LayoutParams(cornerSize, cornerSize)
            )
            floatingCornerOverlay.add(
                floatingCornerTR,
                FrameLayout.LayoutParams(cornerSize, cornerSize)
            )
            floatingCornerOverlay.add(
                floatingCornerBL,
                FrameLayout.LayoutParams(cornerSize, cornerSize)
            )
            floatingCornerOverlay.add(
                floatingCornerBR,
                FrameLayout.LayoutParams(cornerSize, cornerSize)
            )
        }
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        setupFloatingKeyboard()

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)

        setOnClickListener {
            if (isFloatingKeyboardActive && keyboardPrefs.floatingKeyboardHideOnFocusLoss.getValue()) {
                service.requestHideSelf(0)
            }
        }
    }

    private fun setupFloatingKeyboard() {
        if (isFloatingKeyboardActive) enableFloatingKeyboard() else disableFloatingKeyboard()
    }

    private fun enableFloatingKeyboard() {
        floatingKeyboardLayoutApplied = true
        migrateFloatingScaleIfNeeded()
        applyDefaultFloatingPositionIfNeeded()
        configureKeyboardViewForFloating()
        configurePreeditViewForFloating()
        setupFloatingPadding()
        restoreFloatingPosition()

        applyFloatingResize()
        applyFloatingContentScale()

        setupFloatingDragHandle()
        setupFloatingCornerHandles()

        setFloatingResizeMode(false)
        updateFloatingUiVisibility()

        keyboardView.post {
            applyFloatingResize()
            applyFloatingContentScale()
            clampFloatingPosition()
            updateFloatingCornerHandlesPosition()
            updateFloatingPreeditPosition()
        }
    }

    private fun disableFloatingKeyboard() {
        resetKeyboardViewTransform()
        clearFloatingTouchListeners()
        hideFloatingCorners()
        resetFloatingContentScales()
        restoreNonFloatingLayout()
        restorePreeditViewForNonFloating()
        floatingKeyboardLayoutApplied = false
        updateKeyboardBackgroundClip()
        updateFloatingUiVisibility()

    }

    private fun migrateFloatingScaleIfNeeded() {
        if (floatingKeyboardScaleX == 1.0f && floatingKeyboardScaleY == 1.0f && floatingKeyboardScale != 1.0f) {
            floatingKeyboardScaleX = floatingKeyboardScale
            floatingKeyboardScaleY = floatingKeyboardScale
        }
    }

    private fun applyDefaultFloatingPositionIfNeeded() {
        if (floatingKeyboardX == 0 && floatingKeyboardY == 0) {
            floatingKeyboardY = -dp(180)
            if (floatingKeyboardScaleX == 1.0f && floatingKeyboardScaleY == 1.0f) {
                floatingKeyboardScaleX = 0.9f
                floatingKeyboardScaleY = 0.9f
                floatingKeyboardScale = 0.9f
            }
        }
    }

    private fun configureKeyboardViewForFloating() {
        keyboardView.scaleX = 1.0f
        keyboardView.scaleY = 1.0f

        keyboardView.outlineProvider = floatingKeyboardOutlineProvider
        keyboardView.clipToOutline = true
        updateKeyboardBackgroundClip()

        keyboardView.updateLayoutParams<LayoutParams> {
            startToStart = LayoutParams.PARENT_ID
            endToEnd = unset
            topToTop = LayoutParams.PARENT_ID
            bottomToBottom = unset
        }
    }

    private fun setupFloatingPadding() {
        val sidePadding = (keyboardSidePaddingPx + floatingExtraSidePaddingPx)
            .coerceAtLeast(floatingExtraSidePaddingPx)
        leftPaddingSpace.visibility = VISIBLE
        rightPaddingSpace.visibility = VISIBLE
        leftPaddingSpace.updateLayoutParams { width = sidePadding }
        rightPaddingSpace.updateLayoutParams { width = sidePadding }
        windowManager.view.updateLayoutParams<LayoutParams> {
            startToStart = unset
            endToEnd = unset
            startToEndOf(leftPaddingSpace)
            endToStartOf(rightPaddingSpace)
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    private fun restoreFloatingPosition() {
        keyboardView.x = floatingKeyboardX.toFloat()
        keyboardView.y = floatingKeyboardY.toFloat()
        updateFloatingPreeditPosition()
    }

    private fun setupFloatingDragHandle() {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var tracking = false
        var moved = false
        var startRawX = 0f
        var startRawY = 0f
        var startViewX = 0f
        var startViewY = 0f

        floatingHandleBar.setOnTouchListener { v, e ->
            if (e.pointerCount != 1) {
                tracking = false
                return@setOnTouchListener false
            }
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    moved = false
                    startRawX = e.rawX
                    startRawY = e.rawY
                    startViewX = keyboardView.x
                    startViewY = keyboardView.y
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return@setOnTouchListener false
                    val dx = e.rawX - startRawX
                    val dy = e.rawY - startRawY
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    keyboardView.x = startViewX + dx
                    keyboardView.y = startViewY + dy
                    clampFloatingPosition()
                    updateFloatingPreeditPosition()
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    if (!moved && e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        v.performClick()
                        setFloatingResizeMode(!floatingResizeMode)
                        updateFloatingCornerHandlesPosition()
                        true
                    } else {
                        moved
                    }
                }
                else -> false
            }
        }
    }

    private fun setupFloatingCornerHandles() {
        setupCornerHandle(floatingCornerTL, xSign = -1, ySign = -1)
        setupCornerHandle(floatingCornerTR, xSign = 1, ySign = -1)
        setupCornerHandle(floatingCornerBL, xSign = -1, ySign = 1)
        setupCornerHandle(floatingCornerBR, xSign = 1, ySign = 1)
    }

    private fun setupCornerHandle(handle: View, xSign: Int, ySign: Int) {
        handle.setOnTouchListener { _, e ->
            if (!floatingResizeMode) return@setOnTouchListener false
            if (e.pointerCount != 1) return@setOnTouchListener false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val startFactorX = floatingKeyboardScaleX
                    val startFactorY = floatingKeyboardScaleY
                    val baseW = floatingBaseWidthPx().toFloat().coerceAtLeast(1f)
                    val baseAreaH = floatingBaseKeyboardAreaHeightPx().toFloat().coerceAtLeast(1f)
                    val basePadH = keyboardBottomPaddingPx.toFloat().coerceAtLeast(0f)
                    val startTotalH = keyboardView.height.toFloat().coerceAtLeast(1f)
                    val constantH =
                        (startTotalH - baseAreaH * startFactorY - basePadH * startFactorY)
                            .coerceAtLeast(0f)

                    val startX = keyboardView.x
                    val startY = keyboardView.y
                    val startW = keyboardView.width.toFloat().coerceAtLeast(1f)
                    val startH = keyboardView.height.toFloat().coerceAtLeast(1f)

                    val fixedX = if (xSign > 0) startX else (startX + startW)
                    val fixedY = if (ySign > 0) startY else (startY + startH)

                    handle.tag = FloatingCornerResizeState(
                        startRawX = e.rawX,
                        startRawY = e.rawY,
                        startFactorX = startFactorX,
                        startFactorY = startFactorY,
                        baseW = baseW,
                        baseAreaH = baseAreaH,
                        basePadH = basePadH,
                        constantH = constantH,
                        fixedX = fixedX,
                        fixedY = fixedY,
                    )
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val state =
                        handle.tag as? FloatingCornerResizeState ?: return@setOnTouchListener false
                    val dx = (e.rawX - state.startRawX) * xSign
                    val dy = (e.rawY - state.startRawY) * ySign

                    val newFactorX =
                        (state.startFactorX * (1f + (dx / state.baseW))).coerceIn(0.6f, 1.3f)
                    val newFactorY =
                        (state.startFactorY * (1f + (dy / state.baseAreaH))).coerceIn(0.6f, 1.3f)
                    floatingKeyboardScaleX = newFactorX
                    floatingKeyboardScaleY = newFactorY
                    floatingKeyboardScale = (newFactorX + newFactorY) / 2f

                    postApplyFloatingUpdates()

                    val targetW = (state.baseW * newFactorX).coerceAtLeast(dp(240).toFloat())
                    val targetAreaH =
                        (state.baseAreaH * newFactorY).coerceAtLeast(dp(160).toFloat())
                    val targetPadH = (state.basePadH * newFactorY).coerceAtLeast(0f)
                    val targetH = (state.constantH + targetAreaH + targetPadH).coerceAtLeast(1f)

                    val desiredX = if (xSign > 0) state.fixedX else (state.fixedX - targetW)
                    val desiredY = if (ySign > 0) state.fixedY else (state.fixedY - targetH)
                    keyboardView.x = desiredX
                    keyboardView.y = desiredY

                    clampFloatingPosition()
                    updateFloatingCornerHandlesPosition()
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun resetKeyboardViewTransform() {
        keyboardView.scaleX = 1.0f
        keyboardView.scaleY = 1.0f
        keyboardView.translationX = 0f
        keyboardView.translationY = 0f
    }

    private fun clearFloatingTouchListeners() {
        floatingHandleBar.setOnTouchListener(null)
        listOf(
            floatingCornerTL,
            floatingCornerTR,
            floatingCornerBL,
            floatingCornerBR
        ).forEach { it.setOnTouchListener(null) }
    }

    private fun hideFloatingCorners() {
        floatingCornerTL.visibility = GONE
        floatingCornerTR.visibility = GONE
        floatingCornerBL.visibility = GONE
        floatingCornerBR.visibility = GONE
    }

    private fun resetFloatingContentScales() {
        keyboardView.clipToOutline = false
        keyboardView.outlineProvider = ViewOutlineProvider.BACKGROUND
        updateKeyboardBackgroundClip()

        floatingBaseTextSizePx.forEach { (tv, base) ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
        }
        floatingBaseImageScale.forEach { (iv, base) ->
            iv.scaleX = base.first
            iv.scaleY = base.second
        }
        floatingKeyViews.keys.forEach { kv ->
            kv.setMarginScale(1.0f)
        }

        floatingContentRegistered = false
        floatingContentScalePosted = false
        lastAppliedFloatingContentScale = Float.NaN
    }

    private fun restoreNonFloatingLayout() {
        keyboardView.updateLayoutParams<LayoutParams> {
            this.width = LayoutParams.MATCH_PARENT
            startToStart = unset
            endToEnd = unset
            centerHorizontally()
            topToTop = unset
            bottomToBottom = LayoutParams.PARENT_ID
        }
        updateKeyboardSize()
    }

    fun refreshFloatingKeyboardMode() {
        setupFloatingKeyboard()
    }

    private fun floatingBaseWidthPx(): Int {
        val dm = resources.displayMetrics
        val shortSide = min(dm.widthPixels, dm.heightPixels)
        return (shortSide * 0.95f).toInt()
    }

    private fun floatingBaseKeyboardAreaHeightPx(): Int {
        return keyboardHeightPx
    }

    private fun applyFloatingResize() {
        if (!floatingKeyboardLayoutApplied) return
        val width = (floatingBaseWidthPx() * floatingKeyboardScaleX)
            .toInt()
            .coerceAtLeast(dp(240))
        keyboardView.updateLayoutParams<LayoutParams> {
            this.width = width
        }
        windowManager.view.updateLayoutParams {
            height = (floatingBaseKeyboardAreaHeightPx() * floatingKeyboardScaleY)
                .toInt()
                .coerceAtLeast(dp(160))
        }
        bottomPaddingSpace.updateLayoutParams {
            height = (keyboardBottomPaddingPx * floatingKeyboardScaleY)
                .toInt()
                .coerceAtLeast(0)
        }

        updateFloatingPreeditPosition()
    }

    private fun registerFloatingContentViews(v: View) {
        if (floatingContentRegistered) return
        when (v) {
            is KeyView -> {
                floatingKeyViews.putIfAbsent(v, Unit)
            }
            is TextView -> {
                floatingBaseTextSizePx.putIfAbsent(v, v.textSize)
            }
            is ImageView -> {
                if (v !== customBackground) {
                    floatingBaseImageScale.putIfAbsent(v, v.scaleX to v.scaleY)
                }
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                registerFloatingContentViews(v.getChildAt(i))
            }
        }
    }

    private fun ensureFloatingContentRegistered() {
        if (floatingContentRegistered) return
        registerFloatingContentViews(windowManager.view)
        floatingContentRegistered =
            (floatingKeyViews.size + floatingBaseTextSizePx.size + floatingBaseImageScale.size) > 0
    }

    private fun postApplyFloatingUpdates() {
        if (floatingContentScalePosted) return
        floatingContentScalePosted = true
        keyboardView.postOnAnimation {
            floatingContentScalePosted = false
            applyFloatingResize()
            applyFloatingContentScale()
        }
    }

    private fun updateFloatingCornerHandlesPosition() {
        if (!isFloatingKeyboardActive || !floatingKeyboardLayoutApplied) return
        val x = keyboardView.x
        val y = keyboardView.y
        val w = keyboardView.width.toFloat().coerceAtLeast(1f)
        val h = keyboardView.height.toFloat().coerceAtLeast(1f)
        val cornerSize = floatingCornerHandleSizePx.toFloat()
        val outside = dp(8).toFloat()

        floatingCornerTL.x = x - outside
        floatingCornerTL.y = y - outside

        floatingCornerTR.x = x + w - cornerSize + outside
        floatingCornerTR.y = y - outside

        floatingCornerBL.x = x - outside
        floatingCornerBL.y = y + h - cornerSize + outside

        floatingCornerBR.x = x + w - cornerSize + outside
        floatingCornerBR.y = y + h - cornerSize + outside
    }

    private fun clampFloatingPosition() {
        if (!floatingKeyboardLayoutApplied) return
        val parent = keyboardView.parent as? View ?: return
        val w = keyboardView.width.toFloat()
        val h = keyboardView.height.toFloat()
        if (w == 0f || h == 0f) return
        val maxX = (parent.width - w).coerceAtLeast(0f)
        val maxY = (parent.height - h).coerceAtLeast(0f)
        keyboardView.x = keyboardView.x.coerceIn(0f, maxX)
        keyboardView.y = keyboardView.y.coerceIn(0f, maxY)
        floatingKeyboardX = keyboardView.x.toInt()
        floatingKeyboardY = keyboardView.y.toInt()
        updateFloatingCornerHandlesPosition()
        updateFloatingPreeditPosition()
        ensureFloatingContentRegistered()
        floatingKeyViews.keys.forEach { kv ->
            kv.updateBounds()
        }
    }

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
