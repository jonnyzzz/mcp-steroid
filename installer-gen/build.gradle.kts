import de.undercouch.gradle.tasks.download.Download
import groovy.json.JsonSlurper

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Read JDK archives (tar.gz / tar.xz / zip) to inspect their real inner layout in the resolver.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // XZ codec for tar.xz: an OPTIONAL transitive of commons-compress, so it must be declared
    // explicitly — the resolver + install scripts both accept tar.xz, and without this the tar.xz
    // path would throw NoClassDefFoundError instead of working.
    implementation("org.tukaani:xz:1.10")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Render `install.sh` + `install.ps1` from the committed coordinate files. A pure data-merge:
 * no devrig/plugin build dependency. Invoked by the website Makefile (shelling out to ./gradlew)
 * and by :test-integration (which consumes the output dir via a system property). Relative -P
 * paths resolve against the repo root so `-PoutDir=website/static` works from `cd .. && ./gradlew`.
 */
val generateInstaller by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate install.sh + install.ps1 from website/installer/*-coordinates.json."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath

    val outDirProp = project.findProperty("outDir") as String?
    val outDir = if (outDirProp != null) rootProject.file(outDirProp).absolutePath
                 else layout.buildDirectory.dir("installer").get().asFile.absolutePath
    val jdkCoords = rootProject.file((project.findProperty("jdkCoordinatesFile") as String?)
        ?: "website/installer/jdk-coordinates.json").absolutePath
    val devrigCoords = rootProject.file((project.findProperty("devrigCoordinatesFile") as String?)
        ?: "website/installer/devrig-coordinates.json").absolutePath

    inputs.file(jdkCoords)
    inputs.file(devrigCoords)
    inputs.property("version", project.version.toString())
    outputs.dir(outDir)

    args(
        "--out-dir", outDir,
        "--jdk-coordinates", jdkCoords,
        "--devrig-coordinates", devrigCoords,
        "--version", project.version.toString(),
    )
    doFirst { logger.lifecycle("[installer-gen] generateInstaller -> $outDir") }
}

// ── Block 2: coordinate resolvers — Gradle downloads the real artifacts; the resolver inspects them ──

// Pinned download URLs come from the committed coordinate file (parsed at configuration time).
@Suppress("UNCHECKED_CAST")
val jdkPlatformUrls: Map<String, String> = run {
    val f = rootProject.file("website/installer/jdk-coordinates.json")
    val root = JsonSlurper().parse(f) as? Map<String, Any?>
        ?: error("jdk-coordinates.json is not a JSON object: $f")
    val platforms = root["platforms"] as? Map<String, Map<String, Any?>>
        ?: error("jdk-coordinates.json: missing 'platforms' object ($f)")
    platforms.mapValues { (key, e) ->
        (e["url"] as? String) ?: error("jdk-coordinates.json: platform '$key' has no 'url' ($f)")
    }
}

val jdkDownloadDir = layout.buildDirectory.dir("jdk-download")

val downloadAllJdks by tasks.registering {
    group = "installer"
    description = "Download all real JDK packages pinned in website/installer/jdk-coordinates.json."
}

jdkPlatformUrls.forEach { (key, url) ->
    val fileName = url.substringAfterLast('/')
    val dl = tasks.register<Download>("downloadJdk_$key") {
        group = "installer"
        description = "Download the $key JDK package ($fileName)."
        val destFile = jdkDownloadDir.get().asFile.resolve(fileName)
        src(url)
        dest(destFile)
        connectTimeout(30_000)
        readTimeout(15 * 60_000)
        retries(5)
        tempAndMove(true)
        // Vendor artifacts are immutable for a pinned URL; skip the fetch entirely if already on disk
        // (this supersedes onlyIfModified — when present we never re-check, when absent we always fetch).
        onlyIf { !destFile.exists() }
    }
    downloadAllJdks.configure { dependsOn(dl) }
}

// Manual / daily-refresh entrypoint (no automated caller yet — the daily installer-jdk-refresh GH
// Action that invokes this lands in the release-wiring block). The integration test exercises the
// resolver in-process; this task is the production path that regenerates the committed coordinates.
val resolveJdkCoordinates by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Inspect the downloaded JDKs and (re)generate jdk-coordinates.json with real sha256 + javaHomeSubpath."
    dependsOn(downloadAllJdks)
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.resolver.ResolverMainKt")
    classpath = sourceSets["main"].runtimeClasspath

    val source = rootProject.file("website/installer/jdk-coordinates.json").absolutePath
    val out = (project.findProperty("out") as String?)
        ?.let { rootProject.file(it).absolutePath }
        ?: layout.buildDirectory.file("installer-resolved/jdk-coordinates.json").get().asFile.absolutePath
    val argList = mutableListOf("jdk", "--source", source, "--download-dir", jdkDownloadDir.get().asFile.absolutePath, "--out", out)
    (project.findProperty("urlBase") as String?)?.let { argList += listOf("--url-base", it) }
    args(*argList.toTypedArray())
}

// Expose the downloaded-JDK directory so :test-integration can serve the real archives from a side-car.
val jdkDownloadElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-download-dir")) }
}
artifacts {
    add(jdkDownloadElements.name, jdkDownloadDir) { builtBy(downloadAllJdks) }
}

// ── Resolver B: devrig-coordinates from the built :npx-kt devrig package zip (release-time) ──
// Task-scoped coupling only: this resolvable config is resolved ONLY by resolveDevrigCoordinates, so
// generateInstaller (the pure data-merge) stays independent of the devrig/plugin build.
val devrigPackage by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "devrig-package")) }
}

dependencies {
    devrigPackage(project(":npx-kt"))
}

// Manual / release entrypoint (the release task/CI invokes this to refresh devrig-coordinates.json from
// the published artifact). Records the public release URL; sha256 + size are computed from the zip bytes.
val resolveDevrigCoordinates by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Compute devrig-coordinates.json (sha256 + size) from the built :npx-kt devrig package zip."
    dependsOn(devrigPackage)
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.resolver.ResolverMainKt")
    classpath = sourceSets["main"].runtimeClasspath

    val out = (project.findProperty("out") as String?)
        ?.let { rootProject.file(it).absolutePath }
        ?: layout.buildDirectory.file("installer-resolved/devrig-coordinates.json").get().asFile.absolutePath
    val version = project.version.toString()
    val url = (project.findProperty("devrigUrl") as String?)
        ?: "https://github.com/jonnyzzz/mcp-steroid/releases/download/v$version/devrig-$version.zip"
    doFirst {
        args("devrig", "--dist-zip", devrigPackage.singleFile.absolutePath, "--url", url, "--out", out)
    }
}
