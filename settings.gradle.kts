pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        gradlePluginPortal()
    }
}

// Keep the IDE and Gradle project identity stable. The 26.x adapter is deliberately
// an independent Java-25 Gradle project and must not be included from this Java-21 root.
rootProject.name = "OMMT"
