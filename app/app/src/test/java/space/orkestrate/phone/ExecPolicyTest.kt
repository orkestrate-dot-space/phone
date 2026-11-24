package space.orkestrate.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecPolicyTest {
  @Test fun allowsExactReconOnly() {
    assertTrue(ExecPolicy.auto(listOf("date")))
    assertTrue(ExecPolicy.auto(listOf("uname", "-a")))
    assertFalse(ExecPolicy.auto(listOf("uname", "-r")))
    assertFalse(ExecPolicy.auto(listOf("rm", "-rf", "/")))
  }
}
