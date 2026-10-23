plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.flutter.flutter-gradle-plugin")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
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
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "../../app/proguard-rules.pro")
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
kotlin.sourceSets.getByName("main").kotlin.srcDir(prepareNativeCore)
tasks.matching { it.name.startsWith("ksp") || it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(prepareNativeCore)
}
flutter { source = "../.." }
dependencies {
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
