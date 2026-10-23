pluginManagement {
    val properties = java.util.Properties()
    val localProperties = file("local.properties")
    if (localProperties.exists()) localProperties.inputStream().use { properties.load(it) }
    val flutterSdkPath = properties.getProperty("flutter.sdk") ?: System.getenv("FLUTTER_ROOT")
    require(flutterSdkPath != null) { "Set flutter.sdk in android/local.properties or FLUTTER_ROOT" }
    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "PennyPilot"
include(":app")
