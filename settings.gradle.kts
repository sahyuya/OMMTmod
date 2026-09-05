pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        gradlePluginPortal()
    }
}

// Keep the IDE and Gradle project identity stable. The root is the maintained Java-25/26.2
// project; versions/adapter-26 contributes only the thin official-name compatibility source.
rootProject.name = "OMMT"
