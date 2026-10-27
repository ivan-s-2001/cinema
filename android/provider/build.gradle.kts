plugins { id("com.android.library") }
android {
    namespace = "app.cinemahall.tv"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].java.srcDir("../core/app/src/main/java")
    sourceSets["main"].jniLibs.srcDir("../core/app/src/main/jniLibs")
    sourceSets["main"].assets.srcDir("../core/app/src/main/assets")
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libtorrserver.so" } }
}
