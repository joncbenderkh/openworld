package dev.joncbender.openworld

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The inked-map look of the Android chrome (see [InkPalette] for the colors and sizes): ivory controls with an ink
 * edge, serif type, wax-ink accents. Everything is drawn from shapes and a custom switch, not from the app's base
 * theme, which is the old non-Material `Theme.NoTitleBar.Fullscreen` and renders some stock widgets invisibly.
 */
class InkTheme(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(value: Float): Int = (value * density).roundToInt()

    private fun edgePx(widthDp: Float): Int = max(1, (widthDp * density).roundToInt())

    /** Body text, and the face of the glyphs on round buttons. */
    val bodyFace: Typeface = Typeface.SERIF

    /** Titles and button labels: medium weight where the platform has it (API 28), regular below. */
    val displayFace: Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.SERIF, 500, false) else Typeface.SERIF

    // ---- shapes ---------------------------------------------------------------------------------------------------

    /** A filled rounded rectangle with an optional ink edge. */
    fun box(fill: Int, radiusDp: Float, edge: Int? = InkPalette.INK, edgeDp: Float = InkPalette.LINE_DP): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            if (edge != null) setStroke(edgePx(edgeDp), edge)
        }

    fun disc(fill: Int, edge: Int? = InkPalette.INK): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (edge != null) setStroke(edgePx(InkPalette.LINE_DP), edge)
        }

    /**
     * The surface of a bottom sheet: ivory, rounded on top, inked along the top edge only. The drawable is pushed
     * out by one stroke width on the sides and bottom, so those strokes fall outside the view and are clipped.
     */
    fun sheetBackground(): Drawable {
        val line = edgePx(InkPalette.LINE_DP)
        val shape = GradientDrawable().apply {
            setColor(InkPalette.SURFACE)
            val r = InkPalette.RADIUS_SHEET_DP * density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(line, InkPalette.INK)
        }
        return InsetDrawable(shape, -line, 0, -line, -line)
    }

    /** [content] with an ink-colored ripple that stays inside the shape; [mask] gives that shape. */
    private fun rippled(content: Drawable, mask: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf((0x29 shl 24) or (InkPalette.INK and 0xFFFFFF)), content, mask)

    // ---- text -----------------------------------------------------------------------------------------------------

    fun text(text: String, sizeSp: Float = InkPalette.TEXT_BASE_SP, color: Int = InkPalette.INK, face: Typeface = bodyFace): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = face
        }

    fun title(text: String): TextView = text(text, InkPalette.TEXT_XL_SP, face = displayFace)

    fun bodyText(text: String): TextView = text(text, InkPalette.TEXT_MD_SP, InkPalette.TEXT_2)

    fun meta(text: String): TextView = text(text, InkPalette.TEXT_SM_SP, InkPalette.TEXT_MUTED)

    /** A small spaced-out heading above a group, e.g. "WORLD SEED". */
    fun label(text: String): TextView =
        text(text.uppercase(), InkPalette.TEXT_SM_SP, InkPalette.TEXT_META, displayFace).apply { letterSpacing = 0.1f }

    /** A hairline between groups. */
    fun rule(): View =
        View(context).apply {
            setBackgroundColor(InkPalette.BORDER_SOFT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, edgePx(1f))
        }

    // ---- controls -------------------------------------------------------------------------------------------------

    /** A round button printed over the map: ivory, ink edge, a glyph in the middle. 52dp, so a thumb hits it. */
    fun medallion(glyph: String, description: String, onClick: () -> Unit): View =
        TextView(context).apply {
            text = glyph
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(InkPalette.INK)
            background = rippled(disc(InkPalette.SURFACE), disc(Color.WHITE, null))
            elevation = 4f * density
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val size = dp(InkPalette.MAP_BUTTON_DP)
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

    /** A round 48dp close button for a panel or sheet: a warm disc with a cross. */
    fun closeButton(description: String, onClick: () -> Unit): View =
        TextView(context).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(InkPalette.INK)
            background = rippled(disc(InkPalette.SURFACE_WARM, null), disc(Color.WHITE, null))
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val size = dp(InkPalette.TOUCH_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    /** A text button at least 48dp tall: [fill] and [labelColor] with an optional [edge]. */
    private fun button(label: String, fill: Int, labelColor: Int, edge: Int?, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            typeface = displayFace
            textSize = InkPalette.TEXT_BASE_SP
            letterSpacing = 0.02f
            setTextColor(labelColor)
            minimumHeight = dp(InkPalette.TOUCH_DP)
            minHeight = dp(InkPalette.TOUCH_DP)
            setPadding(dp(16f), 0, dp(16f), 0)
            stateListAnimator = null
            background = rippled(box(fill, InkPalette.RADIUS_MD_DP, edge, 1f), box(Color.WHITE, InkPalette.RADIUS_MD_DP, null))
            setOnClickListener { onClick() }
        }

    /** The one main action of a screen: filled with the accent ink. */
    fun primaryButton(label: String, onClick: () -> Unit): Button =
        button(label, InkPalette.ACCENT, InkPalette.ACCENT_ON, InkPalette.ACCENT_ACTIVE, onClick)

    /** An ordinary action: warm ivory with a faint ink edge. */
    fun secondaryButton(label: String, onClick: () -> Unit): Button =
        button(label, InkPalette.SURFACE_WARM, InkPalette.TEXT_2, InkPalette.TEXT_META, onClick)

    /** A quiet action with no fill, e.g. Cancel. */
    fun textButton(label: String, onClick: () -> Unit): Button =
        button(label, Color.TRANSPARENT, InkPalette.TEXT_2, null, onClick)

    /** One choice out of several, shown as a row of segments (e.g. Low / Medium / High). [onSelect] gets the index. */
    fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = object : LinearLayout(context) {
            // Equal shares while every segment keeps its 48 dp; otherwise natural widths (the row overflows, but
            // a constrained row would shrink the later segments below the touch target).
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                val fits = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
                    room >= childCount * dp(InkPalette.TOUCH_DP)
                for (i in 0 until childCount) {
                    val params = getChildAt(i).layoutParams as LayoutParams
                    params.weight = if (fits) 1f else 0f
                    params.width = if (fits) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                }
                super.onMeasure(
                    if (fits) widthMeasureSpec else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    heightMeasureSpec,
                )
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(InkPalette.BACKDROP, InkPalette.RADIUS_MD_DP, InkPalette.BORDER_SOFT, 1f)
            val pad = dp(3f)
            setPadding(pad, pad, pad, pad)
        }
        val segments = labels.map { label ->
            TextView(context).apply {
                text = label
                typeface = displayFace
                textSize = InkPalette.TEXT_BASE_SP
                gravity = Gravity.CENTER
                minHeight = dp(InkPalette.TOUCH_DP)
                minWidth = dp(InkPalette.TOUCH_DP)
                isClickable = true
                isFocusable = true
            }
        }
        fun paint(current: Int) {
            segments.forEachIndexed { i, segment ->
                val on = i == current
                segment.isSelected = on
                segment.setTextColor(if (on) InkPalette.SURFACE else InkPalette.TEXT_2)
                segment.background = rippled(
                    box(if (on) InkPalette.INK else Color.TRANSPARENT, InkPalette.RADIUS_MD_DP - 2f, null),
                    box(Color.WHITE, InkPalette.RADIUS_MD_DP - 2f, null),
                )
            }
        }
        segments.forEachIndexed { i, segment ->
            segment.setOnClickListener {
                paint(i)
                onSelect(i)
            }
            row.addView(segment, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(selected)
        return row
    }

    /** An on/off switch drawn here, so it shows up whatever the app theme is. */
    fun switch(checked: Boolean, onChange: (Boolean) -> Unit): InkSwitch =
        InkSwitch(context, density).also {
            it.isChecked = checked
            it.onChange = onChange
        }

    /** A bar with a segment sliding along it, for work whose length is not known. */
    fun busyBar(): InkBusyBar = InkBusyBar(context, density)

    // ---- dialogs and sheets ---------------------------------------------------------------------------------------------------

    /**
     * A dialog in the middle of a dimmed map: [content] on an ivory card with an ink edge. Returns the dialog; the
     * caller shows it. Tapping the dim area does not dismiss it - it asks something the player must answer.
     */
    fun dialog(content: View): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = FrameLayout(context).apply {
            background = box(InkPalette.SURFACE, InkPalette.RADIUS_LG_DP)
            isClickable = true
            val pad = dp(20f)
            setPadding(pad, dp(24f), pad, dp(12f))
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = FrameLayout(context).apply {
            val margin = dp(24f)
            setPadding(margin, margin, margin, margin)
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(InkPalette.SCRIM))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return dialog
    }

    /**
     * A bottom sheet over a dimmed map: [content] sits on the ivory sheet, a tap on the dim area dismisses it.
     * Returns the dialog; the caller shows it.
     */
    fun sheet(content: View): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(context).apply { setOnClickListener { dialog.dismiss() } }
        val surface = FrameLayout(context).apply {
            background = sheetBackground()
            isClickable = true
            setPadding(dp(20f), dp(12f), dp(20f), dp(InkPalette.EDGE_DP + 12f))
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(
            surface,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(InkPalette.SCRIM))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        return dialog
    }
}

@SuppressLint("ViewConstructor") // only built in code, by InkTheme.switch
/**
 * A 52 x 32dp track with a round thumb, centered in a 48dp-tall touch area (the view takes whatever size its parent
 * gives it, at least 52 x 48dp when free). Checked: ink track, ivory thumb on the right.
 */
class InkSwitch(context: Context, private val density: Float) : View(context) {
    var onChange: ((Boolean) -> Unit)? = null

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((InkPalette.SWITCH_WIDTH_DP * density).roundToInt(), widthMeasureSpec),
            resolveSize((InkPalette.TOUCH_DP * density).roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val trackWidth = InkPalette.SWITCH_WIDTH_DP * density
        val trackHeight = InkPalette.SWITCH_HEIGHT_DP * density
        val left = (width - trackWidth) / 2f
        val top = (height - trackHeight) / 2f
        val edge = InkPalette.LINE_DP * density
        track.set(left + edge / 2f, top + edge / 2f, left + trackWidth - edge / 2f, top + trackHeight - edge / 2f)
        val radius = track.height() / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) InkPalette.INK else InkPalette.SURFACE_WARM
        canvas.drawRoundRect(track, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = edge
        paint.color = if (isChecked) InkPalette.INK else InkPalette.TEXT_2
        canvas.drawRoundRect(track, radius, radius, paint)

        val thumbRadius = 10f * density
        val thumbX = if (isChecked) left + trackWidth - 16f * density else left + 16f * density
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) InkPalette.SURFACE else InkPalette.TEXT_2
        canvas.drawCircle(thumbX, top + trackHeight / 2f, thumbRadius, paint)
    }

    override fun performClick(): Boolean {
        isChecked = !isChecked
        onChange?.invoke(isChecked)
        return super.performClick()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.isChecked = isChecked
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isChecked
    }
}

/** A track with a segment that slides back and forth while the view is on screen. */
class InkBusyBar(context: Context, private val density: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), (8f * density).roundToInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = InkPalette.SURFACE_WARM
        canvas.drawRoundRect(rect, radius, radius, paint)
        val segment = width * 0.3f
        val left = (width - segment) * phase
        rect.set(left, 0f, left + segment, height.toFloat())
        paint.color = InkPalette.INK
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}
