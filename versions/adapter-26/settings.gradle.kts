pluginManagement {
  repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    gradlePluginPortal()
  }
}

val target = gradle.startParameter.projectProperties["minecraft_version"] ?: "unknown"
rootProject.name = "OMMT-26-adapter-${target.replace(Regex("[^A-Za-z0-9]"), "-")}" 
