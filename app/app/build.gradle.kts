import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("io.github.takahirom.roborazzi")
}

val releasePropsFile = file("${System.getProperty("user.home")}/.config/orkestrate/phone-release.properties")
val releaseProps = Properties().apply {
  if (releasePropsFile.exists()) releasePropsFile.inputStream().use { load(it) }
}

android {
  namespace = "space.orkestrate.phone"
  compileSdk = 36

  defaultConfig {
    applicationId = "space.orkestrate.phone"
    minSdk = 29
    targetSdk = 36
    versionCode = 59
    versionName = "0.23.6"
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }

  buildFeatures {
    aidl = true
  }

  signingConfigs {
    create("release") {
      val sf = System.getenv("PHONE_KEYSTORE_PATH") ?: releaseProps.getProperty("storeFile")
      val sp = System.getenv("PHONE_KEYSTORE_PASSWORD") ?: releaseProps.getProperty("storePassword")
      val ka = System.getenv("PHONE_KEY_ALIAS") ?: releaseProps.getProperty("keyAlias")
      val kp = System.getenv("PHONE_KEY_PASSWORD") ?: releaseProps.getProperty("keyPassword")
      if (!sf.isNullOrBlank() && file(sf).exists()) {
        storeFile = file(sf)
        storePassword = sp
        keyAlias = ka
        keyPassword = kp
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val rel = signingConfigs.getByName("release")
      if (rel.storeFile != null) signingConfig = rel
    }
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
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  val camerax = "1.4.2"
  implementation("androidx.camera:camera-core:$camerax")
  implementation("androidx.camera:camera-camera2:$camerax")
  implementation("androidx.camera:camera-lifecycle:$camerax")
  implementation("androidx.camera:camera-view:$camerax")
  implementation("com.google.zxing:core:3.5.3")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.18")
  testImplementation("androidx.test.ext:junit:1.2.1")
  testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.0")
  testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.0")
  testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.40.0")
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
