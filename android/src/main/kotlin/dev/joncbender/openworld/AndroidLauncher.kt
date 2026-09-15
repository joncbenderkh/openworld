package dev.joncbender.openworld

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
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
        // initializeForView (rather than initialize) skips setContentView so the
        // GL surface can be embedded alongside the settings button overlay below.
        val gameView = initializeForView(game, config)

        val root = FrameLayout(this)
        root.addView(
            gameView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        root.addView(buildSettingsButton())
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

    private fun showSettingsDialog() {
        val screen = game.globeScreen
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // A Switch rendered invisible against this dialog on-device (twice, even
        // with explicit tint colors) - probably the app's old, non-Material base
        // theme (Theme.NoTitleBar.Fullscreen) failing to resolve its track/thumb
        // drawables. CheckBox has much simpler, more universally reliable
        // rendering across themes, so use that instead.
        layout.addView(
            CheckBox(this).apply {
                text = "Flip navigation direction"
                setTextColor(Color.WHITE)
                isChecked = screen.navigationFlipped
                setOnCheckedChangeListener { _, isChecked ->
                    Gdx.app.postRunnable { screen.navigationFlipped = isChecked }
                }
            },
        )

        layout.addView(
            TextView(this).apply {
                text = "Resource density"
                setTextColor(Color.WHITE)
            },
        )
        val densityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Sparse" to 0.08f, "Normal" to 0.12f, "Abundant" to 0.20f).forEach { (label, value) ->
            densityRow.addView(
                Button(this).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    // Changing density only matters for tiles rolled at generation time,
                    // so apply it by regenerating immediately rather than waiting for a
                    // separate "new world seed" tap the player might not think to make.
                    setOnClickListener {
                        Gdx.app.postRunnable {
                            screen.resourceDensity = value
                            screen.regenerateWorld()
                        }
                    }
                },
            )
        }
        layout.addView(densityRow)

        layout.addView(
            Button(this).apply {
                text = "New world seed"
                setOnClickListener { Gdx.app.postRunnable { screen.regenerateWorld() } }
            },
        )

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Close", null)
            .show()
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
