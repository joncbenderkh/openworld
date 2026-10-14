package dev.joncbender.openworld

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Toast
import com.badlogic.gdx.Gdx

/** The settings, as an inked bottom sheet within thumb reach. Behavior is the old dialog's; only the look changed. */
class SettingsSheet(
    private val context: Context,
    private val ink: InkTheme,
    private val game: OpenWorldGame,
    private val newWorld: NewWorldFlow,
) {

    fun show() {
        val screen = game.globeScreen
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        lateinit var dialog: Dialog
        content.addView(
            ink.text("").apply {
                background = ink.box(InkPalette.TEXT_META, 2f, null)
                layoutParams = LinearLayout.LayoutParams(ink.dp(36f), ink.dp(4f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = ink.dp(12f)
                }
            },
        )
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ink.title("Settings"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ink.closeButton("Close settings") { dialog.dismiss() })
            },
        )

        val seedInput = EditText(context).apply {
            setText(screen.seed.toString())
            setTextColor(InkPalette.INK)
            textSize = InkPalette.TEXT_MD_SP
            typeface = ink.bodyFace
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            minHeight = ink.dp(InkPalette.TOUCH_DP)
            background = ink.box(InkPalette.BACKDROP, InkPalette.RADIUS_MD_DP, InkPalette.BORDER_SOFT, 1f)
            setPadding(ink.dp(12f), 0, ink.dp(12f), 0)
            contentDescription = "World seed"
        }
        content.addView(group("World seed") {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(seedInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ink.dp(8f) })
                    addView(ink.secondaryButton("Copy") {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("World seed", seedInput.text.toString()))
                        Toast.makeText(context, "Seed copied", Toast.LENGTH_SHORT).show()
                    })
                },
            )
            addView(
                ink.secondaryButton("Use this seed") {
                    val parsed = seedInput.text.toString().trim().toLongOrNull()
                    if (parsed == null) {
                        Toast.makeText(context, "Invalid seed", Toast.LENGTH_SHORT).show()
                    } else {
                        newWorld.confirmReplace {
                            dialog.dismiss()
                            newWorld.begin { screen.regenerateWorld(parsed) }
                        }
                    }
                },
                block(top = 8f),
            )
        })

        content.addView(group("Resource density") {
            val selected = DENSITIES.indexOfFirst { it.second == screen.resourceDensity }.takeIf { it >= 0 } ?: 1
            lateinit var segments: InkSegmentedRow
            segments = ink.segmented(DENSITIES.map { it.first }, selected) { i ->
                val value = DENSITIES[i].second
                newWorld.confirmReplace(onKeep = { segments.show(selected) }) {
                    dialog.dismiss()
                    val newSeed = System.nanoTime()
                    newWorld.begin {
                        screen.resourceDensity = value
                        screen.regenerateWorld(newSeed)
                    }
                }
            }
            addView(segments)
        })

        content.addView(group(null) {
            val flip = ink.switch(screen.navigationFlipped) { on -> Gdx.app.postRunnable { screen.navigationFlipped = on } }
                .apply { contentDescription = "Flip drag direction" }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ink.dp(InkPalette.TOUCH_DP)
            isClickable = true
            setOnClickListener { flip.performClick() }
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ink.text("Flip drag direction", InkPalette.TEXT_MD_SP, face = ink.displayFace))
                    addView(ink.meta("Dragging turns the globe the opposite way"))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ink.dp(16f) },
            )
            addView(flip)
        })

        content.addView(group(null) {
            addView(
                ink.secondaryButton("New world") {
                    newWorld.confirmReplace {
                        dialog.dismiss()
                        val newSeed = System.nanoTime()
                        newWorld.begin { screen.regenerateWorld(newSeed) }
                    }
                },
                block(),
            )
        })

        content.addView(
            ink.meta("v${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_ID}").apply {
                gravity = Gravity.CENTER
                setPadding(0, ink.dp(4f), 0, 0)
            },
        )

        dialog = ink.sheet(ScrollView(context).apply { addView(content) })
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    /** A block of the sheet under a hairline, with an optional label. [fill] adds the group's rows to its body. */
    private fun group(label: String?, fill: LinearLayout.() -> Unit): LinearLayout {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fill()
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ink.dp(16f)
            }
            addView(ink.rule())
            addView(
                if (label != null) ink.label(label).apply { setPadding(0, ink.dp(16f), 0, ink.dp(8f)) } else Space(context),
                if (label != null) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                else LinearLayout.LayoutParams(0, ink.dp(8f)),
            )
            addView(body)
        }
    }

    private fun block(top: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ink.dp(top)
        }

    private companion object {
        val DENSITIES = listOf("Low" to 0.12f, "Medium" to 0.18f, "High" to 0.28f)
    }
}
