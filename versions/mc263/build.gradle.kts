import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    kotlin("jvm") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.17.21"
    id("maven-publish")
}

version = rootProject.version
group = project.property("maven_group") as String

val minecraftVersion = "26.3"
val loaderVersion = "0.20.1"
val kotlinLoaderVersion = "1.14.2+kotlin.2.4.21"
val ommtRoot = rootProject.projectDir
val adapterRoot = projectDir
val fabricApiVersion = "0.165.0+26.3"
val guiImGuiVersion = "26.3-1.2.1+imgui.1.92.7.1"
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
    java.srcDir(ommtRoot.resolve("src/client/java"))
    kotlin.srcDir(ommtRoot.resolve("src/client/kotlin"))
    kotlin.srcDir(adapterRoot.resolve("src/client/kotlin"))
    kotlin.exclude("**/PlaybackPayload.kt")
    kotlin.exclude("**/UploadPayload.kt")
    kotlin.exclude("**/OyasaimusicmiditranslatorClient.kt")
    kotlin.exclude("**/PreviewSoundPlayer.kt")
    resources.srcDir(ommtRoot.resolve("src/client/resources"))
}

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$kotlinLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("cn.enaium:fabric-gui-imgui:$guiImGuiVersion")
    testImplementation(sourceSets["client"].output)
}

val bundledBankZipCandidates = listOf(
    ommtRoot.parentFile.resolve("73e0fc6020a2b160eb8d5f5b27b9e5579a773d9d.zip"),
    ommtRoot.parentFile.resolve("af57205743d4d573bcb2dea2f81b745d30eb6eb3.zip"),
    ommtRoot.parentFile.resolve("OyasaiMusic-26.2-extended.zip"),
    rootProject.layout.projectDirectory.file("73e0fc6020a2b160eb8d5f5b27b9e5579a773d9d.zip").asFile,
    rootProject.layout.projectDirectory.file("af57205743d4d573bcb2dea2f81b745d30eb6eb3.zip").asFile,
)
val bundledBankZip = bundledBankZipCandidates.firstOrNull { it.isFile }

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    inputs.property("fabric_gui_imgui_version", guiImGuiVersion)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
            "kotlin_loader_version" to kotlinLoaderVersion,
            "fabric_gui_imgui_version" to guiImGuiVersion,
        )
    }
    from(formalSoundCatalog) {
        into("assets/oyasaimusicmiditranslator")
    }
    if (bundledBankZip != null) {
        inputs.file(bundledBankZip)
        from(zipTree(bundledBankZip)) {
            eachFile { path = path.replace('\\', '/') }
            include("assets/**")
        }
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
