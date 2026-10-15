package dev.joncbender.openworld

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
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
        game.onTileSelected = { info -> runOnUiThread { tilePanel.show(info) } }
        game.onGenerationStage = { stage -> runOnUiThread { newWorld.stage(stage) } }
        game.onGenerationProgress = { fraction, eta, cancellable ->
            runOnUiThread { newWorld.progress(fraction, eta, cancellable) }
        }
        game.onWorldProblem = { problem, seed -> runOnUiThread { newWorld.problem(problem, seed) } }
        // initializeForView (rather than initialize) skips setContentView so the
        // GL surface can be embedded alongside the settings button overlay below.
        val gameView = initializeForView(game, config)

        val root = FrameLayout(this)
        root.addView(
            gameView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        root.addView(buildSettingsButton())
        root.addView(buildResetOrientationButton())
        root.addView(tilePanel.view)
        root.addView(newWorld.screen)
        setContentView(root)

        goEdgeToEdge()
    }

    private val ink by lazy { InkTheme(this) }

    private fun buildSettingsButton(): View =
        ink.medallion("⚙", "Settings") { settingsSheet.show() }.apply { layoutParams = cornerParams(Gravity.TOP or Gravity.END) }

    /** Spins the globe back to its starting orientation - a standalone button, not a settings option. */
    private fun buildResetOrientationButton(): View =
        ink.medallion("⟲", "Reset orientation") { Gdx.app.postRunnable { game.globeScreen.resetOrientation() } }
            .apply { layoutParams = cornerParams(Gravity.TOP or Gravity.START) }

    private fun cornerParams(gravity: Int): FrameLayout.LayoutParams {
        val size = ink.dp(InkPalette.MAP_BUTTON_DP)
        return FrameLayout.LayoutParams(size, size).apply {
            this.gravity = gravity
            val margin = ink.dp(InkPalette.EDGE_DP + 4f)
            topMargin = margin
            marginStart = margin
            marginEnd = margin
        }
    }

    private val newWorld by lazy { NewWorldFlow(this, ink, game) }

    private val settingsSheet by lazy { SettingsSheet(this, ink, game, newWorld) }

    private val tilePanel: TilePanel by lazy {
        TilePanel(
            this, ink,
            onClose = {
                tilePanel.hide()
                game.globeScreen.clearSelection()
            },
            onFound = { game.globeScreen.foundSettlementOnSelection() },
            onUpgrade = { game.globeScreen.upgradeSettlementOnSelection() },
            onAllocate = { resource, weight -> game.globeScreen.allocateOnSelection(resource, weight) },
            onAutomatic = { game.globeScreen.allocateAutomaticallyOnSelection() },
        )
    }

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
