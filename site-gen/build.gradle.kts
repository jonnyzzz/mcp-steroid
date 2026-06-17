import de.undercouch.gradle.tasks.download.Download

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

// tasks.test is configured at the END of this file — it depends on downloadJdks / devrigPackage, declared below.

// ── The 5 pinned JDK 25 archives. This list is the ONLY thing a maintainer edits on a JDK bump
//    (url + sha256 + version). One cached Download task each; the generator re-verifies the bytes
//    against the sha256 below. No external pinned file, no cross-module wiring. ──
data class Jdk25(val platform: String, val vendor: String, val version: String, val format: String, val url: String, val sha256: String)

val jdk25 = listOf(
    Jdk25("linux-x64", "corretto", "25.0.3.9.1", "tar.gz",
        "https://corretto.aws/downloads/resources/25.0.3.9.1/amazon-corretto-25.0.3.9.1-linux-x64.tar.gz",
        "00486fa402136f8d40512b101c645dd4db9be2b5535171530ad241cd96c1223d"),
    Jdk25("linux-arm64", "corretto", "25.0.3.9.1", "tar.gz",
        "https://corretto.aws/downloads/resources/25.0.3.9.1/amazon-corretto-25.0.3.9.1-linux-aarch64.tar.gz",
        "8b1fd78bbd1f188f3884f580be674727174635252c0d4d6dfa7cd15de51879ce"),
    Jdk25("macos-arm64", "corretto", "25.0.3.9.1", "tar.gz",
        "https://corretto.aws/downloads/resources/25.0.3.9.1/amazon-corretto-25.0.3.9.1-macosx-aarch64.tar.gz",
        "614107ed76e9fb86d62d8cf2686a9cc4b3a11c019502ca3ba605fc5d51f4d7bb"),
    Jdk25("windows-x64", "corretto", "25.0.3.9.1", "zip",
        "https://corretto.aws/downloads/resources/25.0.3.9.1/amazon-corretto-25.0.3.9.1-windows-x64-jdk.zip",
        "3404a8be08f0fdbbd24c9bbdda79ba1ded87b264a833247b2124ac45da1c16e0"),
    Jdk25("windows-arm64", "azul-zulu", "25.0.3", "zip",
        "https://cdn.azul.com/zulu/bin/zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip",
        "60b6b1faa1a93fea8e64b09f2b9ab136a86b02428f004f8378cfb04cd818a0d4"),
)

val jdk25DownloadDir = layout.buildDirectory.dir("jdk25")

/** Downloaded archive file for a pinned JDK (named by URL basename, kept locally to avoid re-fetch). */
fun jdk25File(j: Jdk25) = jdk25DownloadDir.get().asFile.resolve(j.url.substringAfterLast('/'))

val downloadJdks by tasks.registering {
    group = "installer"
    description = "Download the 5 pinned JDK 25 archives into build/jdk25 (cached — immutable pinned URLs)."
}
jdk25.forEach { j ->
    val dl = tasks.register<Download>("downloadJdk_${j.platform}") {
        group = "installer"
        description = "Download the ${j.platform} JDK (${j.vendor} ${j.version})."
        src(j.url)
        dest(jdk25File(j))
        connectTimeout(30_000)
        readTimeout(15 * 60_000)
        retries(5)
        tempAndMove(true)
        inputs.property("sha256", j.sha256) // a pin bump (new sha) invalidates + re-downloads
        onlyIf { !jdk25File(j).exists() }   // immutable pinned URL — skip if already local
    }
    downloadJdks.configure { dependsOn(dl) }
}

/** One `--jdk` value the generator parses: `platform|vendor|version|format|sha256|url|file`. */
fun jdk25Arg(j: Jdk25) = listOf(j.platform, j.vendor, j.version, j.format, j.sha256, j.url, jdk25File(j).absolutePath).joinToString("|")

// ── Generate install.sh + install.ps1: the tool inspects the downloaded JDK files ad-hoc (sha verify +
//    javaHomeSubpath inference) and resolves devrig (local -PdevrigZip override, -PdevrigVersion, else the
//    latest release), baking everything in. No intermediate jdk-coordinates / devrig-coordinates JSON. ──
val generateInstaller by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate install.sh + install.ps1 from the downloaded JDKs + devrig (override or latest release)."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath
    dependsOn(downloadJdks)

    val outDirProp = project.findProperty("outDir") as String?
    val outDir = if (outDirProp != null) rootProject.file(outDirProp).absolutePath
                 else layout.buildDirectory.dir("installer").get().asFile.absolutePath
    val devrigZip = (project.findProperty("devrigZip") as String?)?.let { rootProject.file(it).absolutePath }
    val devrigUrl = project.findProperty("devrigUrl") as String?
    val devrigVersion = project.findProperty("devrigVersion") as String?
    jdk25.forEach { inputs.property("sha-${it.platform}", it.sha256) }
    inputs.property("version", project.version.toString())
    outputs.dir(outDir)

    doFirst {
        val argList = mutableListOf("--out-dir", outDir, "--version", project.version.toString())
        jdk25.forEach { argList += listOf("--jdk", jdk25Arg(it)) }
        if (devrigZip != null) argList += listOf("--devrig-zip", devrigZip)
        devrigUrl?.let { argList += listOf("--devrig-url", it) }
        devrigVersion?.let { argList += listOf("--devrig-version", it) }
        args(argList)
        logger.lifecycle("[site-gen] generateInstaller -> $outDir")
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

// The built :npx-kt devrig zip — resolved ONLY so the installer integration tests can pass it to the
// generator as a local `--devrig-zip` override (the website build instead resolves the latest release).
val devrigPackage by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "devrig-package")) }
}

dependencies {
    devrigPackage(project(":npx-kt"))
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

    dependsOn(downloadJdks, devrigPackage)
    doFirst {
        // The 5 pinned JDK specs (platform|vendor|version|format|sha256|url|file) the generator consumes —
        // the test reuses them to resolve the real downloads + to drive the generator. Joined by newline.
        systemProperty("test.installer.jdk.specs", jdk25.joinToString("\n") { jdk25Arg(it) })
        systemProperty("test.installer.devrig.package.zip", devrigPackage.singleFile.absolutePath)
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
