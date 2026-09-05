import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

val minecraftVersion = project.property("minecraft_version") as String
require(minecraftVersion == "26.2") { "OMMT now supports only Minecraft 26.2" }
val adapterSource = layout.projectDirectory.dir("versions/adapter-26/src/client/kotlin")
val formalSoundCatalog =
    layout.projectDirectory.file("../platform/plugins/OyasaiMusic/src/main/resources/sound-catalog.json")
check(formalSoundCatalog.asFile.isFile) {
    "Required OyasaiMusic sound catalog is missing: ${formalSoundCatalog.asFile}"
}

base { archivesName.set(project.property("archives_base_name") as String) }

tasks.withType<AbstractArchiveTask>().configureEach {
    // Minecraft 26.2's official-name Loom pipeline publishes the playable archive from `jar`.
    if (name == "jar" || name == "remapJar") {
        archiveFileName.set(
            "${project.property("archives_base_name")}-${project.version}-fabric$minecraftVersion.jar",
        )
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
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
    kotlin.srcDir(adapterSource)
    // These four shared files use the pre-26 class names; the adapter supplies the 26.2 versions.
    kotlin.exclude("**/PlaybackPayload.kt")
    kotlin.exclude("**/UploadPayload.kt")
    kotlin.exclude("**/OyasaimusicmiditranslatorClient.kt")
    kotlin.exclude("**/PreviewSoundPlayer.kt")
}

repositories { mavenCentral() }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation(
        "net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}",
    )
    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    implementation(
        "cn.enaium:fabric-gui-imgui:${project.property("fabric_gui_imgui_version")}",
    )
    testImplementation(sourceSets["client"].output)
}

val bundledBankZipCandidates = listOf(
    layout.projectDirectory.file("../73e0fc6020a2b160eb8d5f5b27b9e5579a773d9d.zip").asFile,
    layout.projectDirectory.file("../af57205743d4d573bcb2dea2f81b745d30eb6eb3.zip").asFile,
    layout.projectDirectory.file("../OyasaiMusic-26.2-extended.zip").asFile,
    rootProject.layout.projectDirectory.file("73e0fc6020a2b160eb8d5f5b27b9e5579a773d9d.zip").asFile,
    rootProject.layout.projectDirectory.file("af57205743d4d573bcb2dea2f81b745d30eb6eb3.zip").asFile,
)
val bundledBankZip = bundledBankZipCandidates.firstOrNull { it.isFile }

tasks.processResources {
    inputs.file(formalSoundCatalog)
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", project.property("loader_version"))
    inputs.property("fabric_gui_imgui_version", project.property("fabric_gui_imgui_version"))
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to project.property("loader_version").toString(),
            "kotlin_loader_version" to project.property("kotlin_loader_version").toString(),
            "fabric_gui_imgui_version" to
                project.property("fabric_gui_imgui_version").toString(),
        )
    }
    from(formalSoundCatalog) { into("assets/oyasaimusicmiditranslator") }
    // Bundle the bank resource pack (if present) with path normalization for Windows \ → /.
    // This makes the extended pitch bank available without a server ResourcePackRequest and thus no load screen.
    if (bundledBankZip != null) {
        inputs.file(bundledBankZip)
        from(zipTree(bundledBankZip)) {
            // Normalize Windows backslashes to forward slashes for cross-OS compatibility.
            eachFile { path = path.replace('\\', '/') }
            include("assets/**")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

tasks.test {
    // The pure verifier below exercises codecs without starting a Minecraft runtime.
    enabled = false
}

tasks.register<JavaExec>("verifyUploadCodec") {
    group = "verification"
    description = "Runs pure upload codec golden/malformed/boundary verification."
    dependsOn(tasks.named("compileTestKotlin"))
    classpath =
        sourceSets["test"].output +
            sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
    mainClass.set(
        "com.github.sahyuya.oyasaimusicmiditranslator.interop.UploadV2CodecVerification",
    )
    listOf("nbs_files", "import_files", "import_directory")
        .flatMap { property -> providers.gradleProperty(property).orNull?.split('|').orEmpty() }
        .filter { it.isNotBlank() }
        .let(::args)
}

tasks.named("compileTestKotlin") { dependsOn(tasks.named("compileClientKotlin")) }

tasks.register("build262") {
    group = "ommt"
    description = "Builds and verifies the maintained Minecraft 26.2 OMMT artifact."
    dependsOn(tasks.named("build"), tasks.named("verifyUploadCodec"))
}

tasks.register("verify262") {
    group = "verification"
    description = "Verifies the maintained Minecraft 26.2 OMMT artifact and codec."
    dependsOn(tasks.named("build262"))
}

tasks.register("buildAllSupported") {
    group = "ommt"
    description = "Builds every supported OMMT target (Minecraft 26.2 only)."
    dependsOn(tasks.named("build262"))
}

tasks.register("verifyAllSupported") {
    group = "verification"
    description = "Verifies every supported OMMT target (Minecraft 26.2 only)."
    dependsOn(tasks.named("verify262"))
}

tasks.jar {
    from("LICENSE") { rename { "${it}_${project.base.archivesName.get()}" } }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }
}
