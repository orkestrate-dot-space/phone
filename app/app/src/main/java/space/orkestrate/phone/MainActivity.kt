package space.orkestrate.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v0 shell: status header, name, pairing, last job, collapsed advanced.
 * Visual language mirrors the browse-extension settings ref (single dark card,
 * lowercase section labels, control right) — nothing more.
 */
class MainActivity : Activity(), Relay.Listener {

  private lateinit var store: Store
  private lateinit var relay: Relay

  private lateinit var dot: View
  private lateinit var status: TextView
  private lateinit var identity: TextView
  private lateinit var nameIn: EditText
  private lateinit var relayIn: EditText
  private lateinit var codeIn: EditText
  private lateinit var lastJob: TextView
  private lateinit var advBox: LinearLayout
  private lateinit var advRelay: TextView
  private lateinit var advDevice: TextView
  private lateinit var logView: TextView
  private val logLines = ArrayDeque<String>()

  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    setContentView(R.layout.activity_main)
    store = Store(this)
    relay = Relay(this, store)
    relay.listener = this

    dot = findViewById(R.id.dot)
    status = findViewById(R.id.status)
    identity = findViewById(R.id.identity)
    nameIn = findViewById(R.id.nameIn)
    relayIn = findViewById(R.id.relayIn)
    codeIn = findViewById(R.id.codeIn)
    lastJob = findViewById(R.id.lastJob)
    advBox = findViewById(R.id.advBox)
    advRelay = findViewById(R.id.advRelay)
    advDevice = findViewById(R.id.advDevice)
    logView = findViewById(R.id.logView)

    nameIn.setText(store.name)
    findViewById<Button>(R.id.saveName).setOnClickListener {
      store.name = nameIn.text.toString().ifBlank { android.os.Build.MODEL }
      refreshIdentity()
      log("name → ${store.name}")
    }
    findViewById<Button>(R.id.pairBtn).setOnClickListener {
      val r = relayIn.text.toString()
      val c = codeIn.text.toString()
      if (r.isBlank() || c.isBlank()) { log("relay + code required"); return@setOnClickListener }
      log("pairing…")
      relay.pair(r, c) { ok, msg ->
        runOnUiThread {
          log(msg)
          if (ok) { refreshAll(); relay.connect() }
        }
      }
    }
    findViewById<TextView>(R.id.advToggle).setOnClickListener {
      advBox.visibility = if (advBox.visibility == View.GONE) View.VISIBLE else View.GONE
    }
    findViewById<Button>(R.id.unpairBtn).setOnClickListener {
      relay.disconnect(); store.clear(); refreshAll(); log("unpaired")
    }

    // phone://pair?code=XXXXXXXX&relay=http://host:port
    handleDeepLink(intent)
    refreshAll()
    if (store.paired) relay.connect() else onState("not paired")
  }

  override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleDeepLink(i) }

  private fun handleDeepLink(i: Intent?) {
    val d = i?.data ?: return
    android.util.Log.d("phone", "deep link: $d")
    if (d.scheme != "phone" || d.host != "pair") return
    val code = d.getQueryParameter("code") ?: return
    val r = d.getQueryParameter("relay") ?: return
    relayIn.setText(r); codeIn.setText(code)
    log("deep link → pairing…")
    relay.pair(r, code) { ok, msg ->
      runOnUiThread { log(msg); if (ok) { refreshAll(); relay.connect() } }
    }
  }

  private fun refreshAll() {
    refreshIdentity()
    advRelay.text = store.relayHttp ?: "—"
    advDevice.text = store.deviceId ?: "—"
    if (!store.paired) { onState("not paired"); lastJob.text = "no jobs yet" }
  }

  private fun refreshIdentity() {
    identity.text = "${store.name} is how agents call this phone."
  }

  // ---- Relay.Listener ----
  override fun onState(s: String) = runOnUiThread {
    status.text = s
    dot.setBackgroundColor(if (s.startsWith("connected")) 0xFF34D399.toInt() else 0xFF6B7280.toInt())
  }

  override fun onJob(desc: String) = runOnUiThread { lastJob.text = desc }

  override fun onLog(s: String) = runOnUiThread { log(s) }

  private fun log(s: String) {
    logLines.addLast(s); while (logLines.size > 30) logLines.removeFirst()
    logView.text = logLines.joinToString("\n")
  }
}
