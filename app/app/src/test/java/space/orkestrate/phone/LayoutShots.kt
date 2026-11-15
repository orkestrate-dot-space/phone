package space.orkestrate.phone

import android.view.View
import android.widget.LinearLayout
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Visual iteration loop: renders the real XML in every app state to PNGs
 * the agent can actually look at. Run `recordPaparazziDebug` once, then
 * `verifyPaparazziDebug` after each layout change; inspect the snapshots
 * directory with eyes before shipping any UI build.
 */
class LayoutShots {
  @get:Rule
  val paparazzi = Paparazzi(theme = "Theme.Phone")

  private fun root(): View {
    val v = paparazzi.inflate<LinearLayout>(R.layout.activity_main)
    v.findViewById<android.widget.TextView>(R.id.status).text = "connected — relay + shell"
    v.findViewById<android.widget.TextView>(R.id.identity).text = "SM-M176B is how agents call this phone."
    return v
  }

  @Test fun setup_unpaired() {
    val v = root()
    // paired=false: setup visible, main hidden
    v.findViewById<LinearLayout>(R.id.setupBox).visibility = View.VISIBLE
    v.findViewById<LinearLayout>(R.id.statusBox).visibility = View.GONE
    v.findViewById<LinearLayout>(R.id.tabStatusBody).visibility = View.VISIBLE
    v.findViewById<LinearLayout>(R.id.tabShellBody).visibility = View.GONE
    v.findViewById<LinearLayout>(R.id.tabSettingsBody).visibility = View.GONE
    v.findViewById<android.widget.TextView>(R.id.status).text = "not paired"
    paparazzi.snapshot(v, "setup_unpaired")
  }

  @Test fun status_paired() {
    val v = root()
    v.findViewById<LinearLayout>(R.id.setupBox).visibility = View.GONE
    v.findViewById<LinearLayout>(R.id.statusBox).visibility = View.VISIBLE
    v.findViewById<android.widget.TextView>(R.id.lastJob).text = "battery.get · job_abc123"
    v.findViewById<LinearLayout>(R.id.tabStatusBody).visibility = View.VISIBLE
    v.findViewById<LinearLayout>(R.id.tabShellBody).visibility = View.GONE
    v.findViewById<LinearLayout>(R.id.tabSettingsBody).visibility = View.GONE
    paparazzi.snapshot(v, "status_paired")
  }

  @Test fun shell_tab() {
    val v = root()
    v.findViewById<LinearLayout>(R.id.tabStatusBody).visibility = View.GONE
    v.findViewById<LinearLayout>(R.id.tabShellBody).visibility = View.VISIBLE
    v.findViewById<LinearLayout>(R.id.tabSettingsBody).visibility = View.GONE
    v.findViewById<android.widget.TextView>(R.id.shellStatus).text = "connected — localhost:34977 (shell-UID)"
    paparazzi.snapshot(v, "shell_tab")
  }

  @Test fun settings_tab() {
    val v = root()
    v.findViewById<LinearLayout>(R.id.tabStatusBody).visibility = View.GONE
    v.findViewById<LinearLayout>(R.id.tabShellBody).visibility = View.GONE
    v.findViewById<LinearLayout>(R.id.tabSettingsBody).visibility = View.VISIBLE
    v.findViewById<android.widget.TextView>(R.id.advRelay).text = "https://6ace59c9456b3713.dqxltrsmppfx.opentunnel.xyz"
    v.findViewById<android.widget.TextView>(R.id.advDevice).text = "dev_PzEGMXOOG6St"
    v.findViewById<android.widget.TextView>(R.id.buildInfo).text = "build 36 · 0.18.3"
    paparazzi.snapshot(v, "settings_tab")
  }
}
