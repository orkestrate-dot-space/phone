package space.orkestrate.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingUriTest {
  @Test fun parsesHttpsFragment() {
    val p = PairingUri.parse("https://mobile.orkestrate.space/phone/pair#code=k7qm-4xwe&relay=https://abc.opentunnel.xyz")!!
    assertEquals("K7QM4XWE", p.code)
    assertEquals("https://abc.opentunnel.xyz", p.relay)
  }

  @Test fun parsesPhoneScheme() {
    val p = PairingUri.parse("phone://pair?code=ABCD1234&relay=http%3A%2F%2F10.0.2.2%3A19877")!!
    assertEquals("ABCD1234", p.code)
    assertEquals("http://10.0.2.2:19877", p.relay)
  }

  @Test fun parsesJsonPayload() {
    val p = PairingUri.parse("""{"code":"ab12-cd34","relay":"192.168.1.5:19877"}""")!!
    assertEquals("AB12CD34", p.code)
    assertEquals("http://192.168.1.5:19877", p.relay)
  }

  @Test fun rejectsInvalid() {
    assertNull(PairingUri.parse("https://example.com"))
    assertNull(PairingUri.parse("not a link"))
  }
}
