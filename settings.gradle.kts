pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "SHYNTAX_AUDIO"
include(":app")

// Media3 FFmpeg decoder source is checked out by CI.
val media3SourceDir = System.getenv("MEDIA3_SOURCE_DIR")
if (!media3SourceDir.isNullOrBlank()) {
    (gradle as org.gradle.api.plugins.ExtensionAware).extra["androidxMediaModulePrefix"] = "media3-"
    apply(from = file("$media3SourceDir/core_settings.gradle"))
}
