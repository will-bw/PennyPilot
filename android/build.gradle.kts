allprojects { repositories { google(); mavenCentral() } }
val newBuildDir = rootProject.layout.projectDirectory.dir("../build")
rootProject.layout.buildDirectory.value(newBuildDir)
subprojects { layout.buildDirectory.value(newBuildDir.dir(project.name)); evaluationDependsOn(":app") }
tasks.register<Delete>("clean") { delete(rootProject.layout.buildDirectory) }
