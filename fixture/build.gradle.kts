plugins { id("com.android.application") }
android {
    namespace = "local.voice.fixture"
    compileSdk = 35
    defaultConfig { applicationId = "local.voice.fixture"; minSdk = 33; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
