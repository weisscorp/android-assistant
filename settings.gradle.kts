pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "LocalVoiceAssistant"
include(":app")

// Disposable external UI for accessibility integration tests; not bundled in :app.
include(":fixture")
