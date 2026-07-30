import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

val promptGeneratorClasspath = configurations.create("promptGeneratorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}


// Configuration to resolve intellij-downloader as a classpath for the download task
val ideDownloaderClasspath = configurations.create("ideDownloaderClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// The BTA implementation jar directory from :kotlin-cli — KtBlockCompilationTestBase
// builds its KotlinBuildsSession from these on-disk jars (no runtime extraction).
val kotlincDist = configurations.create("kotlincDist") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "kotlinc-dist"))
    }
}

// Extra classpath entries for the per-block snippet compilation in
// KtBlockCompilationTestBase, for inlined ij-plugin sources that reference
// sibling-module classes. (Historically `ApplyPatchHunk` via the
// since-removed `ApplyPatch.kt`, #206; kept as plumbing.) The
// IDE-home jar walk that builds the rest of the classpath obviously
// doesn't see project-local outputs — passing this configuration's
// resolved files via the `mcp.steroid.extra.classpath` system property
// lets the test append them to the snippet compile classpath.
val ktblockExtraClasspath = configurations.create("ktblockExtraClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    promptGeneratorClasspath(project(":prompt-generator"))

    api(project(":prompts-api"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(project(":test-helper"))
    testImplementation(project(":kotlin-cli"))
    testImplementation(project(":prompt-generator"))

    ideDownloaderClasspath(project(":intellij-downloader"))
    kotlincDist(project(":kotlin-cli"))
}

val generatedSources = layout.buildDirectory.dir("generated/kotlin/prompts")
val generatedTestSources = layout.buildDirectory.dir("generated/kotlin-test/prompts")
val eulaPromptsDir = layout.buildDirectory.dir("eula-prompts")
val prepareEulaPrompt = tasks.register("prepareEulaPrompt") {
    val eulaFile = rootProject.layout.projectDirectory.file("EULA")
    val outputFile = eulaPromptsDir.map { it.file("license/EULA.md") }
    inputs.file(eulaFile)
    outputs.file(outputFile)
    doLast {
        val eulaText = eulaFile.asFile.readText()
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(eulaText)
    }
}

val generatePrompts = tasks.register<JavaExec>("generatePrompts") {
    dependsOn(prepareEulaPrompt)
    classpath = promptGeneratorClasspath
    mainClass.set("com.jonnyzzz.mcpSteroid.promptgen.MainKt")
    args(
        "--input-dir", layout.projectDirectory.dir("src/main/prompts").asFile.absolutePath,
        "--output-dir", generatedSources.get().asFile.absolutePath,
        "--test-output-dir", generatedTestSources.get().asFile.absolutePath,
        "--extra-input-dirs", eulaPromptsDir.get().asFile.absolutePath,
    )
    inputs.dir(layout.projectDirectory.dir("src/main/prompts"))
    inputs.dir(eulaPromptsDir)
    outputs.dir(generatedSources)
    outputs.dir(generatedTestSources)
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatedSources)
}

kotlin.sourceSets.test {
    kotlin.srcDir(generatedTestSources)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generatePrompts)
}

// --- IDE download for KtBlock compilation tests ---

// Each entry: product ID, channel, system property name for the unpacked IDE home
data class IdeDownloadSpec(
    val product: String,
    val channel: String,
    val systemProperty: String,
)

val ideDownloadSpecs = listOf(
    IdeDownloadSpec("idea", "stable", "mcp.steroid.ide.home"),
    IdeDownloadSpec("idea", "eap", "mcp.steroid.ide.eap.home"),
    IdeDownloadSpec("rider", "stable", "mcp.steroid.rider.home"),
    IdeDownloadSpec("rider", "eap", "mcp.steroid.rider.eap.home"),
    IdeDownloadSpec("clion", "stable", "mcp.steroid.clion.home"),
    IdeDownloadSpec("clion", "eap", "mcp.steroid.clion.eap.home"),
    IdeDownloadSpec("pycharm", "stable", "mcp.steroid.pycharm.home"),
    IdeDownloadSpec("pycharm", "eap", "mcp.steroid.pycharm.eap.home"),
)

/**
 * Optional filter: `-Pmcp.prompts.ide.filter=idea:stable` limits IDE downloads and tests
 * to matching entries. Supports both `product` (matches all channels) and `product:channel`
 * (matches one specific entry). Comma-separated. Empty or absent → all IDEs.
 */
val ideFilter = providers.gradleProperty("mcp.prompts.ide.filter")
    .orElse("")
    .get()
    .split(",")
    .map { it.trim().lowercase() }
    .filter { it.isNotEmpty() }
    .toSet()

val filteredIdeDownloadSpecs = if (ideFilter.isEmpty()) {
    ideDownloadSpecs
} else {
    ideDownloadSpecs.filter { spec ->
        // Match "product" (all channels) or "product:channel" (specific)
        spec.product in ideFilter || "${spec.product}:${spec.channel}" in ideFilter
    }
}

val ideDownloadTasks = filteredIdeDownloadSpecs.map { spec ->
    val dirSuffix = "${spec.product}-${spec.channel}"
    val downloadDir = layout.buildDirectory.dir("ide-download-$dirSuffix")
    val unpackDir = layout.buildDirectory.dir("ide-unpack-$dirSuffix")

    val task = tasks.register("downloadAndUnpack-${dirSuffix}", JavaExec::class) {
        group = "verification"
        description = "Download and unpack ${spec.product} (${spec.channel}) for KtBlock compilation tests"
        classpath = ideDownloaderClasspath
        mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
        args(
            "--product", spec.product,
            "--channel", spec.channel,
            "--os", "linux",
            "--output-dir", downloadDir.get().asFile.absolutePath,
            "--unpack-dir", unpackDir.get().asFile.absolutePath,
        )
        outputs.dir(unpackDir)
    }

    Triple(spec, unpackDir, task)
}

/** Gradle task that prints the ideDownloadSpecs list for TC settings validation. */
tasks.register("listIdeDownloadSpecs") {
    group = "verification"
    description = "Print ideDownloadSpecs as product:channel lines for TC settings validation"
    doLast {
        for (spec in ideDownloadSpecs) {
            println("${spec.product}:${spec.channel}")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // On TeamCity, run single-threaded to avoid overwhelming the agent with
    // parallel IDE downloads + snippet compilations. Locally, use 8 cores.
    maxParallelForks = if (System.getenv("TEAMCITY_VERSION") != null) 1 else 8

    // Match the IDE-bundled JBR major version. The KtBlock compilation tests
    // compile with `-jvm-target = java.specification.version` (see
    // `KotlinBuildsSession.DEFAULT_JVM_TARGET`), so the test JVM must
    // line up with the JBR shipped inside each downloaded IDE — otherwise
    // the compiler rejects the IDE's inline bytecode (`cannot inline bytecode
    // built with JVM target N into bytecode that is being built with JVM
    // target M`). All currently-tracked IDEs (stable + EAP, all products)
    // bundle JBR 25.x; if a future IDE bumps to 26 the
    // `assertTestJreMatchesIdeBundled` canary inside the test base will
    // fail loudly and point here.
    //
    // We can't run the test under the IDE's own JBR directly because we
    // download the Linux distribution for portability (no Linux JBR on
    // macOS dev boxes). Pinning the Gradle toolchain is the closest
    // equivalent — foojay-resolver fetches JDK 25 automatically when
    // missing.
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    )

    for ((_, _, task) in ideDownloadTasks) {
        dependsOn(task)
    }
    dependsOn(ktblockExtraClasspath)
    dependsOn(kotlincDist)

    val kotlinVersion = providers.gradleProperty("mcp.kotlinc.version")

    doFirst {
        for ((spec, unpackDir, _) in ideDownloadTasks) {
            val dir = unpackDir.get().asFile
            val home = dir.listFiles()?.firstOrNull { it.isDirectory }
            if (home != null) {
                systemProperty(spec.systemProperty, home.absolutePath)
            }
        }

        // ij-plugin source directory for McpScriptContext/McpScriptBuilder sources
        val ijSources = rootProject.layout.projectDirectory
            .dir("ij-plugin/src/main/kotlin").asFile.absolutePath
        systemProperty("mcp.steroid.ij.sources", ijSources)
        systemProperty("mcp.steroid.kotlin.version", kotlinVersion.get())

        // BTA implementation jar directory (KotlinBuildsSession classpath)
        systemProperty("mcp.steroid.bta.impl.dir", kotlincDist.singleFile.absolutePath)

        // Extra binary classpath entries the per-block snippet compilation
        // needs because the inlined ij-plugin sources reference classes that
        // live in sibling modules (none today; see ktblockExtraClasspath comment).
        // File.pathSeparator-joined absolute paths — same shape kotlinc itself
        // would expect.
        val extraClasspath = ktblockExtraClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }
        systemProperty("mcp.steroid.extra.classpath", extraClasspath)

        // Compilation cache directory
        val cacheDir = layout.buildDirectory.dir("ktblock-cache").get().asFile.absolutePath
        systemProperty("mcp.steroid.ktblock.cache.dir", cacheDir)
    }
}
