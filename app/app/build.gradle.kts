plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("io.github.takahirom.roborazzi")
}

android {
  namespace = "space.orkestrate.phone"
  compileSdk = 36

  defaultConfig {
    applicationId = "space.orkestrate.phone"
    minSdk = 29
    targetSdk = 36
    versionCode = 41
    versionName = "0.18.8"
  }

  buildTypes {
    release { isMinifyEnabled = false }
  }
  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
  implementation(composeBom)
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("com.google.android.material:material:1.12.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.18")
  testImplementation("androidx.test.ext:junit:1.2.1")
  testImplementation("org.robolectric:robolectric:4.18")
  testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.0")
  testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.0")
  testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.40.0")
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
