package dev.joncbender.openworld

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.badlogic.gdx.Gdx

/**
 * Replacing the world: a question first ([confirmReplace]), then a plain screen over the map while it is made
 * ([begin], [stage]). The globe's thread is busy for the whole of generation, so the screen is an ordinary view,
 * fed the stage names by [GlobeScreen.onGenerationStage].
 */
class NewWorldFlow(private val context: Context, private val ink: InkTheme, private val game: OpenWorldGame) {

    private val eyebrow = ink.label("")
    private val stageText = ink.text("", InkPalette.TEXT_SM_SP, InkPalette.TEXT_2, ink.displayFace).apply {
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** What went wrong, rebuilt by [problem]; scrolls where a small screen cannot show it all. */
    private val failed = android.widget.ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
    }

    /** Covers the map and takes its touches while the world is made or has failed. Add it to the root above the globe. */
    val screen: View = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        elevation = ink.dp(24f).toFloat() // above the map buttons, which are raised
        setBackgroundColor(InkPalette.BACKDROP)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = ink.dp(24f)
            setPadding(pad, 0, pad, 0)
            addView(eyebrow.apply { gravity = Gravity.CENTER })
            addView(
                ink.text("Charting a new world", InkPalette.TEXT_XL_SP + 4f, face = ink.displayFace).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ink.dp(8f)
                },
            )
            addView(
                ink.bodyText("Keep the app open. This usually takes 15 to 30 seconds.").apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ink.dp(12f)
                },
            )
            addView(
                ink.busyBar(),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ink.dp(32f)
                },
            )
            addView(
                stageText,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ink.dp(20f)
                },
            )
        }
        working = column
        addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(failed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private lateinit var working: View

    /** Asks before the current world is replaced; [onGenerate] runs on the UI thread if the player agrees, [onKeep] if not. */
    fun confirmReplace(onKeep: () -> Unit = {}, onGenerate: () -> Unit) {
        lateinit var dialog: android.app.Dialog
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ink.title("Chart a new world?"))
            addView(
                ink.bodyText(
                    "This replaces the current world (seed ${game.globeScreen.seed}). Copy the seed first if you want to " +
                        "come back to it. Generating a new world takes about 30 seconds.",
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ink.dp(12f)
                },
            )
            addView(
                actions(
                    ink.textButton("Keep this world") {
                        dialog.dismiss()
                        onKeep()
                    },
                    ink.primaryButton("Generate") {
                        dialog.dismiss()
                        onGenerate()
                    },
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ink.dp(20f)
                },
            )
        }
        dialog = ink.dialog(content)
        // Back and a tap outside are a "no" too, so whatever asked is put back as it was.
        dialog.setOnCancelListener { onKeep() }
        dialog.show()
    }

    /** The two answers side by side at the end of the row, or stacked, Generate first, where they do not fit side by side. */
    private fun actions(keep: View, generate: View): LinearLayout {
        val gap = ink.dp(8f)
        return object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                keep.measure(unbounded, unbounded)
                generate.measure(unbounded, unbounded)
                val stacked = keep.measuredWidth + gap + generate.measuredWidth > room
                if (stacked != (orientation == VERTICAL)) arrange(stacked)
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }

            private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
            private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

            private fun arrange(stacked: Boolean) {
                removeAllViews()
                orientation = if (stacked) VERTICAL else HORIZONTAL
                val first = if (stacked) generate else keep
                val second = if (stacked) keep else generate
                addView(first, LayoutParams(if (stacked) matchParent else wrapContent, wrapContent))
                addView(
                    second,
                    LayoutParams(if (stacked) matchParent else wrapContent, wrapContent).apply {
                        if (stacked) topMargin = gap else marginStart = gap
                    },
                )
            }
        }.apply {
            gravity = Gravity.END
            addView(keep, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                generate,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = gap },
            )
        }
    }

    /** Shows the screen now and then runs [regenerate] on the globe's thread, which hides it again when the world is ready. */
    fun begin(regenerate: () -> Unit) {
        show("Starting")
        Gdx.app.postRunnable(regenerate)
    }

    /** What the world is doing, or null when it is ready; called on the UI thread. */
    fun stage(name: String?) {
        if (name != null) {
            show(name)
        } else {
            if (failed.visibility != View.VISIBLE) screen.visibility = View.GONE
        }
    }

    /** Tells the player the world could not be made; [seed] is the one that was being made. Called on the UI thread. */
    fun problem(problem: WorldProblem, seed: Long) {
        val (title, lede) = when (problem) {
            WorldProblem.NO_ROOM_FOR_NEW ->
                "Not enough free storage" to "The new world doesn't fit on this phone right now. Your current world is safe and unchanged."
            WorldProblem.NO_ROOM ->
                "Not enough free storage" to "There is no room to store this world, so the map stays empty for now."
            WorldProblem.NOT_SAVED -> "The world could not be saved" to "Writing it to storage failed, so it cannot be shown."
            WorldProblem.UNREADABLE -> "The saved world could not be read" to "Generate it again to get it back."
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = ink.dp(24f)
            setPadding(pad, ink.dp(72f), pad, ink.dp(32f))
        }
        val wide = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fun gap(dp: Float) = LinearLayout.LayoutParams(wide).apply { topMargin = ink.dp(dp) }
        column.addView(ink.errorMark())
        column.addView(ink.label(if (problem.worldKept) "World not generated" else "World not shown").apply { gravity = Gravity.CENTER }, gap(24f))
        column.addView(ink.text(title, InkPalette.TEXT_XL_SP + 4f, face = ink.displayFace).apply { gravity = Gravity.CENTER }, gap(8f))
        column.addView(ink.bodyText(lede).apply { gravity = Gravity.CENTER }, gap(12f))
        column.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ink.box(InkPalette.SURFACE, InkPalette.RADIUS_MD_DP, InkPalette.BORDER_SOFT, 1f)
                val pad = ink.dp(16f)
                setPadding(pad, pad, pad, pad)
                if (problem.needsStorage) addView(ink.text("Free up space in Android storage settings, then try again.", InkPalette.TEXT_BASE_SP, InkPalette.TEXT_2))
                addView(
                    ink.meta("Seed $seed is kept, so retrying generates the same world."),
                    LinearLayout.LayoutParams(wide).apply { if (problem.needsStorage) topMargin = ink.dp(8f) },
                )
            },
            gap(24f),
        )
        val leave = {
            failed.visibility = View.GONE
            screen.visibility = View.GONE
            Gdx.app.postRunnable { game.globeScreen.dropPendingWorld() }
        }
        val tryAgain = {
            failed.visibility = View.GONE
            begin { game.globeScreen.retryWorld() }
        }
        if (problem.needsStorage) {
            column.addView(ink.primaryButton("Open storage settings") { openStorageSettings() }, gap(24f))
            column.addView(ink.secondaryButton("Try again") { tryAgain() }, gap(8f))
        } else {
            column.addView(ink.primaryButton("Try again") { tryAgain() }, gap(24f))
        }
        column.addView(ink.textButton(if (problem.worldKept) "Back to current world" else "Close") { leave() }, gap(8f))
        failed.removeAllViews()
        failed.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        working.visibility = View.GONE
        failed.visibility = View.VISIBLE
        screen.visibility = View.VISIBLE
    }

    private fun openStorageSettings() {
        for (action in listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // try the next, wider settings screen
            }
        }
    }

    private fun show(name: String) {
        failed.visibility = View.GONE
        working.visibility = View.VISIBLE
        eyebrow.text = "NEW WORLD · SEED ${game.globeScreen.seed}"
        stageText.text = name
        screen.visibility = View.VISIBLE
    }
}
