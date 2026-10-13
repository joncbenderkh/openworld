package dev.joncbender.openworld

import android.content.Context
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

    /** Covers the map and takes its touches while the world is made. Add it to the root above the globe. */
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
        addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Asks before the current world is replaced; [onGenerate] runs on the UI thread if the player agrees. */
    fun confirmReplace(onGenerate: () -> Unit) {
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
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(ink.textButton("Keep this world") { dialog.dismiss() })
                    addView(
                        ink.primaryButton("Generate") {
                            dialog.dismiss()
                            onGenerate()
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = ink.dp(8f)
                        },
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ink.dp(20f)
                },
            )
        }
        dialog = ink.dialog(content)
        dialog.show()
    }

    /** Shows the screen now and then runs [regenerate] on the globe's thread, which hides it again when the world is ready. */
    fun begin(regenerate: () -> Unit) {
        show("Starting")
        Gdx.app.postRunnable(regenerate)
    }

    /** What the world is doing, or null when it is ready; called on the UI thread. */
    fun stage(name: String?) {
        if (name == null) screen.visibility = View.GONE else show(name)
    }

    private fun show(name: String) {
        eyebrow.text = "NEW WORLD · SEED ${game.globeScreen.seed}"
        stageText.text = name
        screen.visibility = View.VISIBLE
    }
}
