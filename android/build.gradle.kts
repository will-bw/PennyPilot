allprojects { repositories { google(); mavenCentral() } }
val newBuildDir = rootProject.layout.projectDirectory.dir("../build")
rootProject.layout.buildDirectory.value(newBuildDir)
// Configure every output directory before evaluating :app. Android captures
// source directory providers during evaluation, including the reused core.
subprojects { layout.buildDirectory.value(newBuildDir.dir(project.name)) }
subprojects { evaluationDependsOn(":app") }
tasks.register<Delete>("clean") { delete(rootProject.layout.buildDirectory) }
