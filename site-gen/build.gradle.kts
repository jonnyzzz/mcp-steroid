plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
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
    // Docker integration infra (containers, nginx side-car, ProjectHomeDirectory) for the installer
    // bootstrap + real-artifact tests, consolidated into this module.
    testImplementation(project(":test-helper"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
}

// tasks.test is configured at the END of this file — it depends on generateJdkCoordinates / jdk25Downloads
// / devrigPackage, which are declared below.

// ── JDK coordinates GENERATION: :jdk-downloader downloads the 5 JDK 25 archives; this module's resolver
//    inspects them to produce jdk-coordinates.json (sha256 + inferred javaHomeSubpath). Generation lives
//    here (not in jdk-downloader) to avoid a site-gen <-> jdk-downloader project cycle. ──
val jdk25Downloads by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-download-dir")) }
}
val jdk25Pinned by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-pinned")) }
}
dependencies {
    jdk25Downloads(project(":jdk-downloader"))
    jdk25Pinned(project(":jdk-downloader"))
}

val generatedJdkCoordinates = layout.buildDirectory.file("installer-coords/jdk-coordinates.json")

val generateJdkCoordinates by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate jdk-coordinates.json from the JDK 25 archives :jdk-downloader downloaded (verified sha256 + inferred javaHomeSubpath)."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.resolver.ResolverMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    inputs.files(jdk25Downloads).withPropertyName("jdk25Downloads")
    inputs.files(jdk25Pinned).withPropertyName("jdk25Pinned")
    outputs.file(generatedJdkCoordinates)
    doFirst {
        args(
            "jdk",
            "--source", jdk25Pinned.singleFile.absolutePath,
            "--download-dir", jdk25Downloads.singleFile.absolutePath,
            "--out", generatedJdkCoordinates.get().asFile.absolutePath,
        )
    }
}

// Expose the generated jdk-coordinates for the website build + the installer tests.
val jdkCoordinatesElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-coordinates")) }
}
artifacts {
    add(jdkCoordinatesElements.name, generatedJdkCoordinates) { builtBy(generateJdkCoordinates) }
}

// ── Render install.sh + install.ps1 — a pure data-merge. jdk-coordinates defaults to the GENERATED
//    artifact above; -PjdkCoordinatesFile overrides (e.g. tests). ──
val generateInstaller by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate install.sh + install.ps1 from the coordinate files."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath

    val outDirProp = project.findProperty("outDir") as String?
    val outDir = if (outDirProp != null) rootProject.file(outDirProp).absolutePath
                 else layout.buildDirectory.dir("installer").get().asFile.absolutePath
    val jdkCoordsOverride = (project.findProperty("jdkCoordinatesFile") as String?)?.let { rootProject.file(it).absolutePath }
    val devrigCoords = rootProject.file((project.findProperty("devrigCoordinatesFile") as String?)
        ?: "website/installer/devrig-coordinates.json").absolutePath

    if (jdkCoordsOverride == null) {
        dependsOn(generateJdkCoordinates)
        inputs.file(generatedJdkCoordinates)
    } else {
        inputs.file(jdkCoordsOverride)
    }
    inputs.file(devrigCoords)
    inputs.property("version", project.version.toString())
    outputs.dir(outDir)

    doFirst {
        val jdkCoords = jdkCoordsOverride ?: generatedJdkCoordinates.get().asFile.absolutePath
        args(
            "--out-dir", outDir,
            "--jdk-coordinates", jdkCoords,
            "--devrig-coordinates", devrigCoords,
            "--version", project.version.toString(),
        )
        logger.lifecycle("[site-gen] generateInstaller -> $outDir (jdk-coords: $jdkCoords)")
    }
}

// ── Website release artifacts (version.json + updatePlugins.xml) — replaces the former
//    website/Makefile curl + scripts/generate-update-plugins-xml.{sh,py}. Detects the published release,
//    reads the real plugin.xml from the artifact, renders the custom-repo XML. Network task (release
//    lookup + ZIP download), so it is never up-to-date — it always re-runs. ──
val generateSiteArtifacts by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate version.json + updatePlugins.xml for the website from the published GitHub release."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.site.SiteArtifactsKt")
    classpath = sourceSets["main"].runtimeClasspath
    val siteVersion = (project.findProperty("siteVersion") as String?) ?: project.version.toString()
    val outDir = rootProject.file((project.findProperty("outDir") as String?) ?: "website/static").absolutePath
    val notes = rootProject.file("release/notes/$siteVersion.md")
    val argList = mutableListOf("--version", siteVersion, "--out-dir", outDir)
    if (notes.isFile) argList += listOf("--notes", notes.absolutePath)
    (project.findProperty("zipUrl") as String?)?.let { argList += listOf("--zip-url", it) }
    args(argList)
    outputs.upToDateWhen { false } // network task (release lookup + ZIP download) — always re-run
    doFirst { logger.lifecycle("[site-gen] generateSiteArtifacts (v$siteVersion) -> $outDir") }
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
    val explicitUrl = project.findProperty("devrigUrl") as String?
    // Auto-derived from project.version (which carries a -<gitHash>/-SNAPSHOT suffix, NOT a clean
    // vX.Y.Z). Correct only when the release tag includes that exact version; pass -PdevrigUrl=<published
    // asset url> otherwise (the doFirst warns when the auto-derived URL is a non-release version).
    val url = explicitUrl
        ?: "https://github.com/jonnyzzz/mcp-steroid/releases/download/v$version/devrig-$version.zip"

    inputs.files(devrigPackage)
    inputs.property("url", url)
    inputs.property("version", version)
    outputs.file(out)

    doFirst {
        if (explicitUrl == null && ("SNAPSHOT" in version || ".19999" in version)) {
            logger.warn(
                "[resolveDevrigCoordinates] project.version '$version' is not a release version — the " +
                    "auto-derived URL $url will not resolve. Pass -PdevrigUrl=<published asset url> for a real release.",
            )
        }
        args("devrig", "--dist-zip", devrigPackage.singleFile.absolutePath, "--url", url, "--out", out)
    }
}

// ── Tests split into two lanes, both living in this module ────────────────────────────────────────
//  • `test` (src/test)            — FAST, pure-JVM unit tests (CoordinateResolverTest): synthetic
//    archives only, no Docker, no downloads. Stays in the per-OS `ciBuildPluginTests` matrix (cheap,
//    cross-OS), so it must NOT depend on the heavy JDK-download / devrig-build / Docker artifacts.
//  • `installerIntegrationTest`   — HEAVY Docker + real-artifact suite (install.sh/ps1 bootstrap via an
//    nginx side-car + ubuntu/alpine/pwsh containers, the real-artifact lane, and the generated-coords
//    metadata validation). It boots Docker and downloads the 5 real JDK 25 archives, so it joins the
//    serialized `ciIntegrationTests` chain (root build.gradle.kts) — NEVER the parallel plugin matrix —
//    mirroring how :test-integration is isolated (no two Docker test JVMs at once → OOM guard).
tasks.test {
    useJUnitPlatform()
}

val installerIntegrationTestSourceSet = sourceSets.create("installerIntegrationTest") {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

val installerIntegrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Docker-backed installer bootstrap + real-artifact tests + generated jdk-coordinates metadata validation."
    useJUnitPlatform()
    testClassesDirs = installerIntegrationTestSourceSet.output.classesDirs
    classpath = installerIntegrationTestSourceSet.runtimeClasspath
    // Docker side-car + install containers: never run two installer test JVMs at once (RAM/CPU OOM
    // guard, mirrors the repo-wide :test-integration discipline).
    maxParallelForks = 1
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    dependsOn(generateJdkCoordinates, jdk25Downloads, devrigPackage)
    doFirst {
        systemProperty("test.installer.jdk.download.dir", jdk25Downloads.singleFile.absolutePath)
        systemProperty("test.installer.devrig.package.zip", devrigPackage.singleFile.absolutePath)
        systemProperty("test.installer.jdk.coordinates", generatedJdkCoordinates.get().asFile.absolutePath)
    }

    // Heavyweight (Docker + ~1GB JDK downloads): require an explicit invocation — either this task
    // directly or the serialized ciIntegrationTests aggregator — so plain root `./gradlew test` /
    // `check` aggregation never boots Docker. Mirrors :test-integration:test's onlyIf guard.
    onlyIf("Requires explicit :site-gen:installerIntegrationTest or ciIntegrationTests invocation — needs Docker + downloads") {
        val names = gradle.startParameter.taskNames
        names.any { it.contains(":site-gen:installerIntegrationTest") || it == "installerIntegrationTest" } ||
            names.any { it == "ciIntegrationTests" || it.endsWith(":ciIntegrationTests") }
    }
}

// Compile the heavy lane as part of `check` (without running it) so the merge-gate compile check + a
// plain build still catch breakage in those tests even when Docker isn't available to run them.
tasks.named("check") { dependsOn(installerIntegrationTestSourceSet.classesTaskName) }
