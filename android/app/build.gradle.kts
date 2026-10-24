import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val releaseKeyFile = rootProject.file("key.properties")
val releaseKeys = Properties().apply {
    if (releaseKeyFile.exists()) releaseKeyFile.inputStream().use { load(it) }
}
if (!releaseKeyFile.exists() && gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    throw GradleException("Release signing is required. Configure android/key.properties; see docs/RELEASING.md.")
}
ksp { arg("room.schemaLocation", file("../../app/schemas").absolutePath) }
android {
    namespace = "com.pennypilot.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    defaultConfig {
        applicationId = "com.pennypilot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].res.srcDir("../../app/src/main/res")
    // Reuse the existing Room/database/services. Compose never enters this build.
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    signingConfigs {
        if (releaseKeyFile.exists()) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseKeys.getProperty("storeFile")))
                storePassword = requireNotNull(releaseKeys.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseKeys.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseKeys.getProperty("keyPassword"))
            }
        }
    }
    buildTypes {
        release {
            if (releaseKeyFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../../app/proguard-rules.pro", "proguard-rules.pro")
        }
    }
}
// KSP 2 reads source roots independently of Kotlin's exclude predicate. Give both
// compilers the same explicit core-only tree so removed Compose ViewModels never
// produce orphan Hilt factories. Sync tracks the original files as task inputs.
val prepareNativeCore by tasks.registering(Sync::class) {
    from("../../app/src/main/java") {
        exclude("com/pennypilot/app/ui/**")
        exclude("com/pennypilot/app/MainActivity.kt")
        exclude("com/pennypilot/app/domain/model/CategoryColors.kt")
    }
    into(layout.buildDirectory.dir("generated/nativeCore"))
}
// Register the generated Kotlin tree with Android as well so manifest lint can
// resolve the reused Application, services and receivers, just like Kotlin/KSP.
android.sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/nativeCore"))
tasks.matching {
    it.name.startsWith("ksp") || it.name.endsWith("Kotlin") || it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(prepareNativeCore)
}
flutter { source = "../.." }
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    implementation(libs.core.ktx)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-compiler:2.57.2")
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
}
