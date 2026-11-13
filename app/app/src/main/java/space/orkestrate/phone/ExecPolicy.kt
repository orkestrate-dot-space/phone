package space.orkestrate.phone

/**
 * Locked-down auto-approve policy. EXACT argv match only: no prefixes, no
 * globs, no arguments the agent chooses. Every entry is read-only recon
 * with bounded, non-sensitive output. Anything not on this list raises the
 * approval card; anything outside the executor allowlist is rejected outright.
 *
 * Changing this list changes what runs without a tap — treat edits as
 * security reviews, not feature work.
 */
object ExecPolicy {
  private val AUTO: Set<List<String>> = setOf(
    listOf("date"),
    listOf("uname", "-a"),
    listOf("uptime"),
    listOf("getprop", "ro.build.version.release"),
    listOf("getprop", "ro.product.model"),
    listOf("getprop", "ro.build.version.sdk"),
    listOf("cat", "/proc/version"),
  )

  fun auto(argv: List<String>): Boolean = argv in AUTO
}
