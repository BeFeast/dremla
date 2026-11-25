import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.befeast.dremla.resolver"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig { minSdk = 28 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; checkDependencies = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    testImplementation("junit:junit:4.13.2")
    // Android supplies org.json; host tests use its JVM implementation.
    testImplementation("org.json:json:20240303")
}
tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "1g"
    systemProperty("fixtures", rootProject.file("../../contracts/fixtures").absolutePath)
    testLogging { events("passed", "skipped", "failed") }
}
