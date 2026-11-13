plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "space.orkestrate.phone"
  compileSdk = 36

  defaultConfig {
    applicationId = "space.orkestrate.phone"
    minSdk = 29
    targetSdk = 36
    versionCode = 13
    versionName = "0.13.0"
  }

  buildTypes {
    release { isMinifyEnabled = false }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
