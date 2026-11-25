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
        // UI-only device checks (app/src/androidTest); never part of the shipped APK.
        testInstrumentationRunner = "com.befeast.sleepmusic.DeviceUiTestRunner"
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
        isCoreLibraryDesugaringEnabled = true
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
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
    testImplementation("org.json:json:20240303")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.withType<Test>().configureEach {
    // Shared build hosts: one test JVM at a time.
    systemProperty("fixtures", rootProject.file("contracts/fixtures").absolutePath)
    maxParallelForks = 1
    maxHeapSize = "2g"
}

// Inspect the resolved shipping graph, including transitives, for the privacy gate.
tasks.register("writeRuntimeDependencies") {
    val report = layout.buildDirectory.file("reports/privacy/runtime.txt")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        val coordinates = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "coreLibraryDesugaring").filter { configurations.findByName(it) != null }.flatMap { name ->
            val configuration = configurations.getByName(name)
            val artifacts = configuration.resolvedConfiguration.resolvedArtifacts
            val moduleFiles = artifacts.map { it.file }.toSet()
            check(configuration.files.all { it in moduleFiles }) {
                "Unreviewed local runtime file dependency"
            }
            artifacts.map {
                "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}:${it.moduleVersion.id.version}"
            }
        }.distinct().sorted()
        report.get().asFile.apply { parentFile.mkdirs(); writeText(coordinates.joinToString("\n", postfix = "\n")) }
    }
}
