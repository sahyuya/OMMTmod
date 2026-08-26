import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    id("fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = (findProperty("java_version")?.toString() ?: "21").toInt()
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
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

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
    mavenCentral()
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    add("mappings", "net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    add("modImplementation", "net.fabricmc:fabric-loader:${project.property("loader_version")}")
    add("modImplementation", "net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Minecraft 1.21.11-aware Dear ImGui integration. This owns the render/input bridge so OMMT
    // does not have to inject raw OpenGL calls into Minecraft's extracted GUI render pipeline.
    add("modImplementation", "cn.enaium:fabric-gui-imgui:${project.property("fabric_gui_imgui_version")}")

    // Pure editor-model verification reuses the compiled client source set without starting MC.
    testImplementation(sourceSets["client"].output)
}

tasks.processResources {
    // The formal server catalog is the single source of truth for fixed Minecraft sound patterns.
    // Embed an immutable copy in the release JAR so runtime selection never depends on server I/O.
    val formalSoundCatalog = rootProject.layout.projectDirectory.file("../platform/plugins/OyasaiMusic/src/main/resources/sound-catalog.json")
    check(formalSoundCatalog.asFile.isFile) { "Required OyasaiMusic sound catalog is missing: ${formalSoundCatalog.asFile}" }
    inputs.file(formalSoundCatalog)
    from(formalSoundCatalog) {
        into("assets/oyasaimusicmiditranslator")
    }
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    inputs.property("fabric_gui_imgui_version", project.property("fabric_gui_imgui_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to (project.property("minecraft_version") as String),
            "loader_version" to (project.property("loader_version") as String),
            "kotlin_loader_version" to (project.property("kotlin_loader_version") as String),
            "fabric_gui_imgui_version" to (project.property("fabric_gui_imgui_version") as String),
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.test {
    // Fabric Loom's split runtime does not expose Kotlin-only test classes to the JUnit worker.
    // The pure codec verifier below runs on the exact test runtime classpath instead.
    enabled = false
}

tasks.register<JavaExec>("verifyUploadCodec") {
    group = "verification"
    description = "Runs pure upload codec golden/malformed/boundary verification."
    dependsOn(tasks.named("compileTestKotlin"))
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["client"].runtimeClasspath
    mainClass.set("com.github.sahyuya.oyasaimusicmiditranslator.interop.UploadV2CodecVerification")
}

tasks.named("compileTestKotlin") {
    dependsOn(tasks.named("compileClientKotlin"))
}

/** Build and verify only the maintained 1.21.11 artifact (Java 21). */
tasks.register("build12111") {
    group = "ommt"
    description = "Builds and verifies the Minecraft 1.21.11 OMMT artifact."
    dependsOn(tasks.named("build"), tasks.named("verifyUploadCodec"))
}

/** The 26.x adapter is an isolated Java-25 build; do not load it into the Java-21 Loom daemon. */
fun register26Target(name: String, minecraft: String) =
    tasks.register<Exec>(name) {
        group = "ommt"
        description = "Builds and verifies the Minecraft $minecraft OMMT artifact."
        val adapterProjectDir = file("versions/adapter-26").absoluteFile
        val adapterWrapper = adapterProjectDir.resolve("gradlew.bat")
        check(adapterWrapper.isFile) {
            "Missing isolated Gradle wrapper for the Java-25 adapter: $adapterWrapper"
        }
        workingDir = adapterProjectDir
        isIgnoreExitValue = false
        commandLine(
            "cmd", "/d", "/c",
            "call \"${adapterWrapper.absolutePath}\" --no-daemon --console=plain --project-dir \"${adapterProjectDir.absolutePath}\" \"-Pminecraft_version=$minecraft\" clean build verifyUploadCodec",
        )
    }

val build2612 = register26Target("build2612", "26.1.2")
val build262 = register26Target("build262", "26.2")
build2612.configure { mustRunAfter(tasks.named("build12111")) }
build262.configure { mustRunAfter(build2612) }

tasks.register("verify2612") {
    group = "verification"
    description = "Verifies the Minecraft 26.1.2 OMMT artifact and codec."
    dependsOn(build2612)
}

tasks.register("verify262") {
    group = "verification"
    description = "Verifies the Minecraft 26.2 OMMT artifact and codec."
    dependsOn(build262)
}

tasks.register("buildAllSupported") {
    group = "ommt"
    description = "Builds all supported OMMT artifacts: 1.21.11, 26.1.2 and 26.2."
    dependsOn(tasks.named("build12111"), build2612, build262)
}

tasks.register("verifyAllSupported") {
    group = "verification"
    description = "Runs build and upload-codec verification for every supported OMMT target."
    dependsOn(tasks.named("build12111"), tasks.named("verify2612"), tasks.named("verify262"))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
