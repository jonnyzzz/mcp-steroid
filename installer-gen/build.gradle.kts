plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

dependencies {
    // JDK detection (the data model) + installer-script generation. NO project() dependencies on
    // purpose: the generator tasks compile only this module, never the rest of the build.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-core:3.3.2")
    implementation("io.ktor:ktor-client-cio:3.3.2")
    // BouncyCastle PGP: verify the detached OpenPGP signatures (Corretto .sig, Azul signature-binary).
    // Pin the latest (1.84) — past the 1.77/1.78-era CVEs (CVE-2024-29857/30171/30172/34447); bcpg pulls
    // bcprov-jdk18on transitively at the same version.
    implementation("org.bouncycastle:bcpg-jdk18on:1.84")
    // Apache Commons Compress: stream tar.gz / zip entries to locate each JDK's inner JAVA_HOME (bin/java).
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The installerIntegrationTest source set drives Docker via :test-helper (flows in via
    // testRuntimeClasspath, which the source set extends below).
    testImplementation(project(":test-helper"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

// HEAVY Docker installer-bootstrap suite: runs the GENERATED install.sh end-to-end against an nginx
// side-car (no real JDK download — synthetic model + tiny fixtures). Isolated in its own source set so
// it is NOT swept into the parallel plugin test matrix (mirrors :test-integration's discipline).
val installerIntegrationTestSourceSet = sourceSets.create("installerIntegrationTest") {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output + configurations["testRuntimeClasspath"]
    runtimeClasspath += output + compileClasspath
}

val installerIntegrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Docker-backed installer bootstrap test — runs the generated install.sh end-to-end."
    useJUnitPlatform()
    testClassesDirs = installerIntegrationTestSourceSet.output.classesDirs
    classpath = installerIntegrationTestSourceSet.runtimeClasspath
    // Never run two Docker test JVMs at once (RAM/CPU OOM guard, repo-wide :test-integration discipline).
    maxParallelForks = 1
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    // Heavyweight (Docker): require an explicit invocation so plain root `./gradlew test`/`check` never
    // boots Docker. Mirrors :test-integration:test's onlyIf guard.
    onlyIf("Requires explicit :installer-gen:installerIntegrationTest or ciIntegrationTests invocation — needs Docker") {
        gradle.startParameter.taskNames.any {
            it.contains(":installer-gen:installerIntegrationTest") || it == "installerIntegrationTest" ||
                it == "ciIntegrationTests" || it.endsWith(":ciIntegrationTests")
        }
    }
}

// Compile the heavy lane as part of `check` (without running it) so the merge-gate compile check + a
// plain build still catch breakage even when Docker isn't available to run it.
tasks.named("check") { dependsOn(installerIntegrationTestSourceSet.classesTaskName) }

// The JDK download cache MUST live outside any `build/` folder so the multi-hundred-MB JDK archives
// survive `clean` and are shared across runs/branches. The Gradle user home is the natural home; the
// path is computed here and handed to the generator via --cache-dir (the generator never guesses it).
val jdkDownloadCacheDir: java.io.File = gradle.gradleUserHomeDir.resolve("caches/mcp-steroid/installer-gen-jdk")

// Resolve all JDK builds (Amazon Corretto 25 + Azul Zulu 25) into the version-pinned data model JSON.
// Vendor-natural validation (detached OpenPGP signatures) happens inside the generator; downloads are
// cached in jdkDownloadCacheDir. No project() deps — compiles only this module.
val generateJdkModel by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Resolve all JDK builds (Corretto + Azul) into the JDK data model JSON (PGP-verified, cached)."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.JdkModelMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // JDK archives are read fully into memory (one ~230 MB array at a time) to hash + scan them.
    maxHeapSize = "2g"

    val outFile = layout.buildDirectory.file("jdk-model/jdk-model.json")
    outputs.file(outFile)
    // The live vendor sources can publish a new build at any time, so never treat this as up-to-date.
    outputs.upToDateWhen { false }

    doFirst {
        jdkDownloadCacheDir.mkdirs()
        args = listOf(
            "--cache-dir", jdkDownloadCacheDir.absolutePath,
            "--out", outFile.get().asFile.absolutePath,
        )
    }
}

// Generate install.sh + install.ps1 into website/build/generated-static. Resolves the 5 JDKs (cached,
// PGP-verified) and the devrig coordinates (latest GitHub release by default), then bakes the per-platform
// table into the scripts. Knows all paths from the project layout — callers invoke it with no args.
val generateInstaller by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate install.sh + install.ps1 into website/build/generated-static (JDKs PGP-verified + cached; devrig from the latest release)."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "2g" // resolveAllJdks holds one ~230 MB archive at a time

    val versionFile = rootProject.layout.projectDirectory.file("VERSION")
    // Same build/ output dir as generateWebsite (Hugo merges it via its `staticDir` list) — never the
    // tracked source tree, so no per-file .gitignore is needed.
    val outDir = rootProject.layout.projectDirectory.dir("website/build/generated-static")
    inputs.file(versionFile)
    // Always re-run: the published devrig release + the live JDK builds can change without VERSION changing.
    outputs.upToDateWhen { false }

    doFirst {
        val version = versionFile.asFile.readText().trim()
        require(version.isNotEmpty()) { "VERSION file (${versionFile.asFile}) is empty" }
        outDir.asFile.mkdirs()
        jdkDownloadCacheDir.mkdirs()
        args = listOf(
            "--out-dir", outDir.asFile.absolutePath,
            "--version", version,
            "--cache-dir", jdkDownloadCacheDir.absolutePath,
        )
    }
}
