package dev.joncbender.openworld

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Small badges printed on the map, one per alert ("2 starving"): a column under the top-left corner, empty (and out of the
 * way) when all is well. A tap goes to the first settlement the alert is about.
 */
class AlertStrip(
    context: Context,
    private val ink: InkTheme,
    private val onAlert: (Alert) -> Unit,
) {
    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        val margin = ink.dp(InkPalette.EDGE_DP + 4f)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            // Below the reset medallion at the same corner.
            topMargin = margin + ink.dp(InkPalette.MAP_BUTTON_DP) + ink.dp(8f)
            marginStart = margin
        }
    }
    private val column get() = view as LinearLayout

    fun show(alerts: List<Alert>) {
        column.removeAllViews()
        alerts.forEach { alert ->
            column.addView(
                ink.secondaryButton(alert.label()) { onAlert(alert) }.apply {
                    contentDescription = "${alert.label()}: show the first"
                    elevation = ink.dp(4f).toFloat()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ink.dp(8f) },
            )
        }
        view.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
    }
}
