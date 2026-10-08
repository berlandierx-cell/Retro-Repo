pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "RetroIso"
include(":app")

val winlatorRuntime = file(".runtime/winlator/app")
if (winlatorRuntime.isDirectory) {
    include(":winlator-runtime")
    project(":winlator-runtime").projectDir = winlatorRuntime
}
