package dev.joncbender.openworld

import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration

class AndroidLauncher : AndroidApplication() {

    private lateinit var game: OpenWorldGame

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useAccelerometer = false
            useCompass = false
        }
        game = OpenWorldGame()
        // Set before initializeForView() so it's already there by the time
        // create() runs on the GL thread and constructs globeScreen.
        game.onTileSelected = { info -> runOnUiThread { showTileInfo(info) } }
        // initializeForView (rather than initialize) skips setContentView so the
        // GL surface can be embedded alongside the settings button overlay below.
        val gameView = initializeForView(game, config)

        val root = FrameLayout(this)
        root.addView(
            gameView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        root.addView(buildSettingsButton())
        root.addView(buildTileInfoPanel())
        setContentView(root)

        goEdgeToEdge()
    }

    private fun buildSettingsButton(): View {
        val density = resources.displayMetrics.density
        val button = Button(this).apply {
            text = "⚙"
            textSize = 20f
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setTextColor(Color.WHITE)
            setOnClickListener { showSettingsDialog() }
        }
        button.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            val margin = (16 * density).toInt()
            topMargin = margin
            rightMargin = margin
        }
        return button
    }

    private object SettingsPalette {
        val PANEL_BG = Color.argb(245, 26, 28, 34)
        val ACCENT = Color.argb(255, 235, 168, 77)
        val PILL_UNSELECTED = Color.argb(255, 47, 51, 60)
        const val TEXT_PRIMARY = Color.WHITE
        val TEXT_SECONDARY = Color.argb(178, 255, 255, 255)
        val TEXT_FOOTER = Color.argb(120, 255, 255, 255)
        val DIVIDER = Color.argb(40, 255, 255, 255)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedDrawable(fillColor: Int, cornerRadiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fillColor)
            cornerRadius = dp(cornerRadiusDp).toFloat()
        }

    private fun pillButton(text: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (selected) Color.BLACK else SettingsPalette.TEXT_PRIMARY)
            background = roundedDrawable(if (selected) SettingsPalette.ACCENT else SettingsPalette.PILL_UNSELECTED, 10f)
            setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
            setOnClickListener { onClick() }
        }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(SettingsPalette.TEXT_SECONDARY)
            textSize = 13f
            setPadding(0, dp(16f), 0, dp(6f))
        }

    private fun divider(): View =
        View(this).apply {
            setBackgroundColor(SettingsPalette.DIVIDER)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                topMargin = dp(16f)
            }
        }

    private fun showSettingsDialog() {
        val screen = game.globeScreen
        val padding = dp(24f)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = roundedDrawable(SettingsPalette.PANEL_BG, 20f)
        }

        layout.addView(
            TextView(this).apply {
                text = "Settings"
                setTextColor(SettingsPalette.TEXT_PRIMARY)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )

        // A Switch rendered invisible against this dialog on-device (twice, even
        // with explicit tint colors) - probably the app's old, non-Material base
        // theme (Theme.NoTitleBar.Fullscreen) failing to resolve its track/thumb
        // drawables. CheckBox has much simpler, more universally reliable
        // rendering across themes, so use that instead.
        layout.addView(
            CheckBox(this).apply {
                text = "Flip navigation direction"
                setTextColor(SettingsPalette.TEXT_PRIMARY)
                isChecked = screen.navigationFlipped
                setPadding(paddingLeft, dp(16f), paddingRight, 0)
                setOnCheckedChangeListener { _, isChecked ->
                    Gdx.app.postRunnable { screen.navigationFlipped = isChecked }
                }
            },
        )

        layout.addView(sectionLabel("Resource density"))
        val densityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val densityOptions = listOf("Sparse" to 0.12f, "Normal" to 0.18f, "Abundant" to 0.28f)
        lateinit var densityButtons: List<Button>
        densityButtons = densityOptions.map { (label, value) ->
            pillButton(label, selected = screen.resourceDensity == value) {
                Gdx.app.postRunnable {
                    screen.resourceDensity = value
                    screen.regenerateWorld()
                }
                densityButtons.forEachIndexed { i, button ->
                    val isSelected = densityOptions[i].second == value
                    button.setTextColor(if (isSelected) Color.BLACK else SettingsPalette.TEXT_PRIMARY)
                    button.background = roundedDrawable(
                        if (isSelected) SettingsPalette.ACCENT else SettingsPalette.PILL_UNSELECTED,
                        10f,
                    )
                }
            }
        }
        densityButtons.forEach { button ->
            val margin = dp(6f)
            densityRow.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = margin
                    marginEnd = margin
                },
            )
        }
        layout.addView(densityRow)

        layout.addView(divider())

        layout.addView(
            Button(this).apply {
                text = "New world seed"
                isAllCaps = false
                setTextColor(Color.BLACK)
                background = roundedDrawable(SettingsPalette.ACCENT, 10f)
                setOnClickListener { Gdx.app.postRunnable { screen.regenerateWorld() } }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16f)
            },
        )

        layout.addView(
            TextView(this).apply {
                text = "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_ID}"
                setTextColor(SettingsPalette.TEXT_FOOTER)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(20f), 0, 0)
            },
        )

        val margin = dp(24f)
        val outer = FrameLayout(this).apply {
            setPadding(margin, margin, margin, margin)
            addView(layout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setContentView(outer)
            window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }.show()
    }

    private lateinit var tileInfoPanel: LinearLayout
    private lateinit var tileBiomeText: TextView
    private lateinit var tileResourceText: TextView

    /**
     * A small non-blocking panel docked to the bottom of the screen, instead
     * of a modal dialog: tapping a tile is going to be a frequent, repeated
     * action, and having to dismiss a "Close" button every single time was
     * tedious. This just updates in place on every subsequent tap and stays
     * out of the way (the globe underneath remains fully pannable/zoomable)
     * until explicitly dismissed with the X. Extend this layout with more
     * views/buttons here once actions (e.g. harvest, build) exist.
     */
    private fun buildTileInfoPanel(): View {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()

        tileBiomeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        tileResourceText = TextView(this).apply { setTextColor(Color.WHITE) }

        val closeButton = Button(this).apply {
            text = "✕"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener { tileInfoPanel.visibility = View.GONE }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tileBiomeText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeButton)
        }

        tileInfoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(padding, padding, padding, padding)
            visibility = View.GONE
            addView(headerRow)
            addView(tileResourceText)
        }
        tileInfoPanel.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            val margin = (16 * density).toInt()
            bottomMargin = margin
        }
        return tileInfoPanel
    }

    private fun showTileInfo(info: TileInfo) {
        tileBiomeText.text = "Biome: ${info.biome.name.toDisplayName()}"
        tileResourceText.text = if (info.resources.isEmpty()) {
            "Resources: none"
        } else {
            "Resources: ${info.resources.joinToString(", ") { it.name.toDisplayName() }}"
        }
        tileInfoPanel.visibility = View.VISIBLE
    }

    private fun String.toDisplayName(): String =
        lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /**
     * Dragging toward a pole naturally drags toward the top/bottom screen edge,
     * which is exactly where Android's system gesture navigation (back swipe,
     * recents swipe) intercepts touches before the app ever sees them - so
     * without this, "spin to the pole" silently loses the gesture to the OS.
     * Hides the system bars and excludes the whole window from gesture
     * navigation so every drag reaches the game.
     */
    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.post {
                window.decorView.systemGestureExclusionRects =
                    listOf(Rect(0, 0, window.decorView.width, window.decorView.height))
            }
        }
    }
}
