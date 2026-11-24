package space.orkestrate.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterTest {
  @Test fun parsesReleaseJsonWithExplicitAndDefaultApkUrl() {
    val full = """
      {
        "versionCode": 45,
        "versionName": "0.21.0",
        "apkUrl": "https://mobile.orkestrate.space/orkestrate-phone.apk",
        "notes": "Auto-update"
      }
    """.trimIndent()
    val a = AppUpdater.parseReleaseJson(full)
    assertEquals(45, a.versionCode)
    assertEquals("0.21.0", a.versionName)
    assertEquals("https://mobile.orkestrate.space/orkestrate-phone.apk", a.apkUrl)
    assertEquals("Auto-update", a.notes)

    val minimal = """{"versionCode": 46, "versionName": "0.22.0"}"""
    val b = AppUpdater.parseReleaseJson(minimal)
    assertEquals(46, b.versionCode)
    assertEquals(AppUpdater.DEFAULT_APK_URL, b.apkUrl)
  }
}
