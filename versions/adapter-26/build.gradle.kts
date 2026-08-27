import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

val minecraftVersion = project.property("minecraft_version") as String
require(minecraftVersion == "26.1.2" || minecraftVersion == "26.2") {
    "OMMT 26 adapter supports only Minecraft 26.1.2 and 26.2"
}
val ommtRoot = rootProject.projectDir.parentFile.parentFile
val adapterRoot = rootProject.projectDir.parentFile.resolve("adapter-26")
val fabricApiVersion = if (minecraftVersion == "26.1.2") "0.155.2+26.1.2" else "0.158.0+26.2"
val guiImGuiVersion = if (minecraftVersion == "26.1.2") "26.1-1.0.11+imgui.1.92.0" else "26.2-1.1.0+imgui.1.92.0"
val formalSoundCatalog = ommtRoot.parentFile.resolve("platform/plugins/OyasaiMusic/src/main/resources/sound-catalog.json")
check(formalSoundCatalog.isFile) { "Required OyasaiMusic sound catalog is missing: $formalSoundCatalog" }

layout.buildDirectory.set(layout.projectDirectory.dir("build/$minecraftVersion"))

base { archivesName.set("${project.property("archives_base_name")}-mc$minecraftVersion") }

tasks.withType<AbstractArchiveTask>().configureEach {
    // Loom's 26.x official-name pipeline publishes the playable archive from `jar`
    // (there is no remapJar task), while retaining this for future Loom compatibility.
    if (name == "jar" || name == "remapJar") {
        archiveFileName.set("${project.property("archives_base_name")}-${project.version}-fabric$minecraftVersion.jar")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

sourceSets {
    named("main") {
        java.setSrcDirs(emptyList<String>())
        kotlin.srcDir(ommtRoot.resolve("src/main/kotlin"))
        resources.srcDir(ommtRoot.resolve("src/main/resources"))
    }
    named("test") {
        java.setSrcDirs(emptyList<String>())
        kotlin.srcDir(ommtRoot.resolve("src/test/kotlin"))
        resources.srcDir(ommtRoot.resolve("src/test/resources"))
    }
}

loom {
    splitEnvironmentSourceSets()
    mods {
        register("oyasaimusicmiditranslator") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

sourceSets.named("client") {
    java.setSrcDirs(emptyList<String>())
    kotlin.srcDir(ommtRoot.resolve("src/client/kotlin"))
    kotlin.srcDir(adapterRoot.resolve("src/client/kotlin"))
    kotlin.exclude("**/PlaybackPayload.kt")
    kotlin.exclude("**/UploadPayload.kt")
    kotlin.exclude("**/OyasaimusicmiditranslatorClient.kt")
    resources.srcDir(ommtRoot.resolve("src/client/resources"))
}

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("cn.enaium:fabric-gui-imgui:$guiImGuiVersion")
    testImplementation(sourceSets["client"].output)
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", project.property("loader_version"))
    inputs.property("fabric_gui_imgui_version", guiImGuiVersion)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to project.property("loader_version").toString(),
            "kotlin_loader_version" to project.property("kotlin_loader_version").toString(),
            "fabric_gui_imgui_version" to guiImGuiVersion,
        )
    }
    from(formalSoundCatalog) {
        into("assets/oyasaimusicmiditranslator")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_25) }

tasks.test { enabled = false }

tasks.register<JavaExec>("verifyUploadCodec") {
    group = "verification"
    description = "Runs pure packet upload codec verification."
    dependsOn(tasks.named("compileTestKotlin"))
    classpath =
        sourceSets["test"].output +
            sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
    mainClass.set("com.github.sahyuya.oyasaimusicmiditranslator.interop.UploadV2CodecVerification")
    workingDir = ommtRoot
}

tasks.named("compileTestKotlin") { dependsOn(tasks.named("compileClientKotlin")) }

tasks.jar {
    from(ommtRoot.resolve("LICENSE")) { rename { "${it}_${project.base.archivesName.get()}" } }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.base.archivesName.get()
            from(components["java"])
        }
    }
}
