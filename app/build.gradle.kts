import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// The release workflow sets these with -Pdremla.versionCode=<int> -Pdremla.versionName=<semver>.
val dremlaVersionCode: Int = providers.gradleProperty("dremla.versionCode")
    .map { value -> value.toIntOrNull() ?: error("dremla.versionCode must be an integer, got '$value'") }
    .getOrElse(1)
val dremlaVersionName: String = providers.gradleProperty("dremla.versionName").getOrElse("0.1.0")

android {
    namespace = "com.befeast.sleepmusic"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.befeast.sleepmusic"
        minSdk = 28
        targetSdk = 34
        versionCode = dremlaVersionCode
        versionName = dremlaVersionName
    }

    buildTypes {
        release {
            // Unsigned on purpose: release signing happens in the release workflow with key
            // material from the secret store, never from this build script.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compose pulls androidx.graphics:graphics-path, which ships libandroidx.graphics.path.so
        // for every ABI. The APK must contain no lib/** entries, so the library is dropped.
        // Consequence on every API level, the API 34 target box included: PathIteratorImpl loads
        // the library in its static initializer (and its conic conversion is native), so any
        // Compose path iteration throws UnsatisfiedLinkError at runtime: Path.iterator(),
        // PathEasing, PathHitTester, Path.toSvg(), Path.divide(). App code must not use them;
        // scripts/check-forbidden-apis.sh enforces it. See docs/ci.md (Native libraries).
        jniLibs.excludes += "**/libandroidx.graphics.path.so"
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

roborazzi {
    // Committed references; verifyRoborazziDebug compares against them.
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(composeBom)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Shared build hosts: one test JVM at a time.
    maxParallelForks = 1
    maxHeapSize = "2g"
}
