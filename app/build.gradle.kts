plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "local.voice"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "local.voice"
        minSdk = 33
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.gradleProperty("helmetVersionCode").orElse("109").get().toInt()
        versionName = providers.gradleProperty("helmetVersionName").orElse("0.42.7").get()
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions {
        jvmTarget = "17"
        // sherpa JNI looks up the typed invoke(FloatArray): Integer callback.
        freeCompilerArgs += "-Xlambdas=class"
    }
}
dependencies {
    implementation("org.pytorch:pytorch_android:2.1.0")
    implementation("com.ibm.icu:icu4j:75.1")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(files("libs/tesseract4android-4.9.0.aar"))
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
}
