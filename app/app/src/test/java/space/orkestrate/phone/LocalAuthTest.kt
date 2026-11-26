package space.orkestrate.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAuthTest {
  @Test fun tokenIs128BitsAndUnique() {
    val a = LocalAuth.generateToken()
    val b = LocalAuth.generateToken()
    assertEquals(32, a.length)
    assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    assertNotEquals(a, b)
  }

  @Test fun bearerMatchesOnlyExactBearer() {
    val t = LocalAuth.generateToken()
    assertTrue(LocalAuth.bearerMatches("Bearer $t", t))
    assertFalse(LocalAuth.bearerMatches(null, t))
    assertFalse(LocalAuth.bearerMatches("", t))
    assertFalse(LocalAuth.bearerMatches("Bearer wrong", t))
    assertFalse(LocalAuth.bearerMatches(t, t))
    assertFalse(LocalAuth.bearerMatches("bearer $t", t))
  }

  @Test fun upstreamBearerSwapsLocalForRealKey() {
    val local = LocalAuth.generateToken()
    assertEquals("Bearer real-key", LocalAuth.upstreamBearer("Bearer $local", local, "real-key"))
    assertNull(LocalAuth.upstreamBearer("Bearer wrong", local, "real-key"))
    assertNull(LocalAuth.upstreamBearer(null, local, "real-key"))
    assertNull(LocalAuth.upstreamBearer("Bearer $local", local, null))
    assertNull(LocalAuth.upstreamBearer("Bearer $local", local, "  "))
  }

  @Test fun shellDecisionMirrorsRelayTier() {
    // Allowlisted recon with auto-approve on: no card.
    assertFalse(LocalAuth.shellNeedsCard(true, listOf("date")))
    assertFalse(LocalAuth.shellNeedsCard(true, listOf("getprop", "ro.build.version.release")))
    // Same argv with auto-approve off: card.
    assertTrue(LocalAuth.shellNeedsCard(false, listOf("date")))
    // Non-allowlisted: always card, even with auto-approve on.
    assertTrue(LocalAuth.shellNeedsCard(true, listOf("rm", "-rf", "/")))
    assertTrue(LocalAuth.shellNeedsCard(true, listOf("sh", "-c", "date")))
    assertTrue(LocalAuth.shellNeedsCard(true, emptyList()))
    // Tokenizer feeds the check: plain recon tokenizes to the allowlist entry.
    assertFalse(LocalAuth.shellNeedsCard(true, LocalAuth.tokenizeShell("date")))
    assertTrue(LocalAuth.shellNeedsCard(true, LocalAuth.tokenizeShell("date; rm -rf /")))
  }
}
