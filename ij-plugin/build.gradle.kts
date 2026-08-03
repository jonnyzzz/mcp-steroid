@file:Suppress("HasPlatformType")

import com.jonnyzzz.mcpSteroid.gradle.*
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeTarget
import java.util.concurrent.ConcurrentHashMap
import com.jonnyzzz.mcpSteroid.ideDownloader.McpSteroidIdeTargets
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveAndUnpackLocally
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.*

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform")
}


val isReleaseBuild = rootProject.extra["isReleaseBuild"] as Boolean

@Suppress("UNCHECKED_CAST")
val releaseNotesText: Provider<String>? = rootProject.extra["releaseNotesText"] as? Provider<String>

val releaseNotesVersion = providers.gradleProperty("mcp.release.notes.version")
    .orElse(rootProject.file("VERSION").readText().trim())
    .get()
val releaseNotesFile = rootProject.layout.projectDirectory.file("release/notes/$releaseNotesVersion.md")

val targetIdeProductRaw = providers.gradleProperty("mcp.platform.product").orElse("idea").get()
// Default flows from the single source of truth in :intellij-downloader
// (`McpSteroidIdeTargets.buildTarget.version`). The Gradle property override
// lets CI build against a specific version (e.g. on the 262 verifier path).
val targetIdeVersion = providers.gradleProperty("mcp.platform.version")
    .orElse(McpSteroidIdeTargets.buildTarget.version)
    .get()
val targetIdeProduct = when (targetIdeProductRaw.trim().lowercase()) {
    "idea", "iiu", "intellij", "intellijidea", "intellijideaultimate" -> JetBrainsIdeProduct.IntelliJIdeaUltimate
    "pycharm", "pcp", "python" -> JetBrainsIdeProduct.PyCharm
    else -> error(
        "Unsupported mcp.platform.product='$targetIdeProductRaw'. " +
                "Use one of: idea, pycharm."
    )
}

// Map the IPGP-side product enum to the intellij-downloader product. The build
// IDE selector (line ~140) and the Plugin Verifier loop (line ~287) both need
// this mapping so the verifier IDEs match the build IDE — running the
// IDEA verifier against a PyCharm-built plugin checks compatibility against
// the wrong API surface and would miss PyCharm-side issues that the runtime
// compat test catches at test time but the verifier should catch at build time.
//
// Closes audit #11 ("PyCharm-side verifier matrix. `verifierTargets` is
// IDEA-only. `-Pmcp.platform.product=pycharm` switches build IDE to PyCharm
// but Plugin Verifier still runs against IDEA").
val verifierIdeProduct: IdeProduct = when (targetIdeProduct) {
    JetBrainsIdeProduct.IntelliJIdeaUltimate -> IdeProduct.IntelliJIdea
    JetBrainsIdeProduct.PyCharm -> IdeProduct.PyCharm
    JetBrainsIdeProduct.GoLand,
    JetBrainsIdeProduct.WebStorm,
    -> error("Plugin build targets IntelliJ IDEA or PyCharm only. GoLand/WebStorm are for integration tests.")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Libraries provided by IntelliJ platform - exclude from bundling
configurations.named("implementation") {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains", module = "annotations")
    exclude(group = "org.slf4j")
}

// IDE archive download + unpack staging dirs, shared between the main build IDE
// and the verifier IDEs. The unpacked tree is keyed by full build + os + arch
// (see `LocalIdeProvisioner.ideRootFolderName`) so multiple host platforms can
// share the same on-disk cache without colliding.
val localIdesBaseDir = rootProject.layout.buildDirectory.dir("local-ides").get().asFile
val ideArchivesDir = rootProject.layout.buildDirectory.dir("ide-archives").get().asFile

// Build target: matrix default, or a synthetic IdeTarget when CI overrides
// `-Pmcp.platform.version=…` with a one-off version (e.g. an exact 262 build).
// Rolling cross-major tags (LATEST-*) are rejected here so an override can't
// reintroduce the silent-slide behaviour the matrix forbids.
val buildIdeTarget: IdeTarget = run {
    if (targetIdeVersion == McpSteroidIdeTargets.buildTarget.version) {
        McpSteroidIdeTargets.buildTarget
    } else {
        require(!targetIdeVersion.contains("LATEST")) {
            "mcp.platform.version='$targetIdeVersion' looks like a rolling tag. " +
                "Use a named per-major spelling (e.g. '262-EAP-SNAPSHOT') or an exact build number."
        }
        IdeTarget(major = "override", version = targetIdeVersion)
    }
}

// Memoized provisioner cache keyed by (target, product). The build IDE and
// the first verifier entry are usually the same target (commit 3's matrix
// invariant); the cache prevents duplicate products-API + checksum lookups.
//
// ConcurrentHashMap.computeIfAbsent is required — since commit 6f669e38 the
// Provider-deferred local() resolution may fire from multiple Gradle worker
// threads concurrently (one per verifierTargets entry + the build IDE +
// each verifyBundledKotlinxRuntime sub-task). A vanilla HashMap getOrPut
// races at that point.
data class LocalIdeKey(val target: IdeTarget, val product: IdeProduct)
val localIdeCache = ConcurrentHashMap<LocalIdeKey, File>()
fun ideRootFor(
    target: IdeTarget,
    product: IdeProduct = IdeProduct.IntelliJIdea,
): File = localIdeCache.computeIfAbsent(LocalIdeKey(target, product)) { key ->
    resolveAndUnpackLocally(
        target = key.target,
        downloadDir = ideArchivesDir,
        unpackBaseDir = localIdesBaseDir,
        product = key.product,
    )
}

/**
 * Lazy `Provider<File>` form of [ideRootFor] for the IPGP `local(...)`
 * selector. IPGP only calls `.get()` when the dependency graph actually
 * needs the IDE — i.e. during real task execution (compileKotlin,
 * prepareSandbox, verifyPlugin, runIde), not at every Gradle
 * invocation. `./gradlew help` and `./gradlew tasks` skip the
 * products-API GET + SHA verification entirely.
 *
 * The shared [localIdeCache] keeps the memoization across providers,
 * so the build IDE and the matching verifier entry still resolve
 * exactly once per build.
 */
fun ideRootProviderFor(
    target: IdeTarget,
    product: IdeProduct = IdeProduct.IntelliJIdea,
): Provider<File> =
    providers.provider { ideRootFor(target, product) }

// Consume kotlinc distribution from kotlin-cli subproject
val kotlincDist = configurations.create("kotlincDist") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "kotlinc-dist"))
    }
}

dependencies {
    intellijPlatform {
        when (targetIdeProduct) {
            // IntelliJ Ultimate goes through the in-repo `intellij-downloader`:
            // archive resolution + download + unpack happen at script-eval time
            // and the unpacked IDE root is fed to IPGP's `local(file)` selector.
            // `useInstaller = true` is no longer applicable (we own the archive).
            JetBrainsIdeProduct.IntelliJIdeaUltimate -> local(ideRootProviderFor(buildIdeTarget))
            // PyCharm Professional also routes through intellij-downloader.
            // Folder name becomes PY-<build>-<os>-<arch>; the resolver hits
            // the same products API on the PCP product code.
            JetBrainsIdeProduct.PyCharm -> local(ideRootProviderFor(buildIdeTarget, IdeProduct.PyCharm))
            JetBrainsIdeProduct.GoLand,
            JetBrainsIdeProduct.WebStorm,
            -> error("Plugin build targets IntelliJ IDEA or PyCharm only. GoLand/WebStorm are for integration tests.")
        }
        // Java + Kotlin plugins are TEST-ONLY. Two safety nets keep
        // production code free of either plugin's types:
        // `NoForbiddenPluginImportsTest` (compile-time) and
        // `PluginRuntimeCompatibilityTest.runtime compat pycharm*` (PyCharm
        // doesn't ship them; the .zip still loads). `testBundledPlugin`
        // puts the jars on the test classpath via
        // `intellijPlatformTestBundledPlugins`, never the main IDE classpath.
        testBundledPlugin("com.intellij.java")
        testBundledPlugin("org.jetbrains.kotlin")
        // Direct (compile-time) access to IntelliJ's bundled MCP server plugin so
        // `IntelliJMcpServerProbe` can call McpServerService.getInstance() without
        // reflection. The plugin is bundled in IDEA 2025.3+ but the **user can
        // disable it at runtime**, so the probe still gates every call site behind
        // `PluginManagerCore.getPluginSet().enabledPlugins`.
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }

    implementation(project(":mcp-core"))
    // Ktor server + McpHttpTransport, transitively brings :mcp-core in
    implementation(project(":mcp-http"))
    // Transport-agnostic tool-handler metadata + registrations (empty for now —
    // classes will migrate across from :ij-plugin in the steps 6-13 refactor series).
    implementation(project(":mcp-steroid-server"))

    // IDE-free file-storage core for ExecutionStorage. The IntelliJ-side
    // IjExecutionStorage service wraps the generic class with project-scoped
    // path + identity providers.
    implementation(project(":execution-storage"))

    // Prompt base classes + generated prompt code
    implementation(project(":prompts"))

    // Kotlinc utility classes (KotlincArgFile, KotlincCommandLineBuilder)
    implementation(project(":kotlin-cli"))

    // Kotlinc binary distribution for plugin sandbox
    kotlincDist(project(":kotlin-cli"))

    // OCR common models shared with ocr-tesseract CLI
    implementation(project(":ocr-common"))

    // AI agent MCP server configuration helpers
    implementation(project(":ai-agents"))

    // PostHog analytics
    implementation("com.posthog:posthog-server:2.3.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":intellij-downloader"))
    testImplementation(project(":test-helper"))

    // https://mvnrepository.com/artifact/org.testcontainers/testcontainers-bom
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.2"))
    testImplementation("org.testcontainers:testcontainers")

    // Ktor client for MCP SSE transport tests
    val ktorVersion = "3.3.2"
    testImplementation("io.ktor:ktor-client-core:$ktorVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}

// Dedicated source set for Docker-based CLI integration tests (CliClaudeIntegrationTest,
// CliCodexIntegrationTest, CliGeminiIntegrationTest, etc.). These extend BasePlatformTestCase
// and need the full IntelliJ Platform test framework classpath, but they spin up Docker
// containers and require API keys, so they are NOT part of the default `:ij-plugin:test` run.
// Invoke explicitly via `./gradlew :ij-plugin:integrationTest`.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output +
            sourceSets["test"].compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets["test"].runtimeClasspath
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

kotlin {
    jvmToolchain(25)
}

val generatedSourcesPath = layout.buildDirectory.dir("generated/kotlin")

// Generate metadata with encoded version
val generateMetadata = tasks.register<GenerateMetadataTask>("generateMetadata") {
    group = "build"
    description = "Generate plugin metadata with encoded version"

    versionString.set(version.toString())
    inputs.property("version", version)
    outputFile.set(generatedSourcesPath.map { it.file("PluginMetadata.kt") })
}

// Add generated sources to main source set and make kotlin compilation depend on it
kotlin.sourceSets.main {
    kotlin.srcDir(generatedSourcesPath)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateMetadata)
}

intellijPlatform {
    projectName = rootProject.name
    caching {
        ides {
            enabled = true
        }
    }
    buildSearchableOptions = false
    pluginConfiguration {
        name = "MCP Steroid"
        version = project.version.toString()
        if (releaseNotesText != null) {
            changeNotes = releaseNotesText
        }

        ideaVersion {
            // KEEP IN SYNC with `MANAGED_BACKEND_MIN_SUPPORTED_BUILD` in
            // intellij-downloader/.../CompatibilityFloor.kt — enforced by
            // PluginCompatibilityFloorTest. 252 + 253 are deprecated;
            // the plugin builds against and supports 261 onward (see
            // docs/262-EAP-PLAN.md, commit 5).
            sinceBuild = "261"
            untilBuild = null
        }
    }

    pluginVerification {
        // IPGP 2.15+ expanded the default failureLevel with INTERNAL_API_USAGES and
        // OVERRIDE_ONLY_API_USAGES. IU-262 marks PluginManagerCore.getPlugin/loadedPlugins
        // @ApiStatus.Internal, and the replacement its KDoc points to (PluginDetailsService)
        // does not exist in 261 — the two call sites (PluginDescriptorProvider,
        // ScriptClassLoaderFactory) cannot move off the internal API while 261 stays
        // supported. Keep OVERRIDE_ONLY as an active guard; internal-API usages stay
        // visible in the verifier report without failing the build.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            // Verifier IDEs go through `intellij-downloader` too. Each entry is
            // downloaded + unpacked into `build/local-ides/<P>-<build>-<os>-<arch>/`
            // (where <P> is IU for IDEA, PY for PyCharm) and fed to IPGP via
            // `local(file)`. The per-major matrix lives in
            // `McpSteroidIdeTargets.verifierTargets` so adding 263 EAP later is
            // a single-place edit covered by `McpSteroidIdeTargetsTest`. The
            // product axis follows `targetIdeProduct` so a PyCharm build
            // verifies against PyCharm IDEs, not IDEA ones (audit #11).
            McpSteroidIdeTargets.verifierTargets.forEach { target ->
                local(ideRootProviderFor(target, verifierIdeProduct))
            }
        }
    }
}

// Register a dedicated `integrationTest` task via the IntelliJ Platform testing DSL so it
// gets its own sandbox (prepareSandbox_integrationTest) and the proper IDE JVM argument
// providers, while compiling from `src/integrationTest/kotlin` instead of `src/test/kotlin`.
intellijPlatformTesting {
    testIde {
        register("integrationTest") {
            // Populates the suffixed intellijPlatformTestDependencies_integrationTest
            // configuration with opentest4j + IntelliJ test framework JARs. Without this
            // the test JVM hits NoClassDefFoundError on org.opentest4j.AssertionFailedError.
            testFramework(TestFrameworkType.Platform)

            task {
                group = "verification"
                description = "Runs Docker-based CLI integration tests (Claude/Codex/Gemini). " +
                        "Requires Docker and API keys. Not run by default `:ij-plugin:test`."
                useJUnit()

                // Replace (not append) testClassesDirs: the TestIdeTask default includes
                // the plugin's instrumented default-test-set classes — keeping them would
                // run every regular test alongside the Cli*IntegrationTest classes.
                testClassesDirs = integrationTest.output.classesDirs

                // Additive on classpath: preserve the IntelliJ Platform JARs +
                // test framework that TestIdeTask.configuration (registration-time lambda)
                // already wired up, then add this source set's classes and its runtime deps
                // on top for our own tests (testcontainers, ktor-client, :test-helper, …).
                classpath += integrationTest.output + integrationTest.runtimeClasspath
            }
        }
    }
}

// Isolate MCP execution storage for every in-process IntelliJ Platform test JVM
// in this module (`test`, `integrationTest`, and any future Test task). Plugin
// code persists execution folders via StoragePaths, which defaults to the REAL
// ~/.mcp-steroid/runs of whoever runs the tests. The "mcp.steroid.storage.path"
// registry key resolves user-stored values first, then System.getProperty(key),
// then the bundled default — so one JVM-level system property redirects ALL test
// classes without per-class setUp code (see RegistryValue.resolveNotRequiredValue).
// The per-run directory is computed inside an argument provider: fresh on every
// execution, and NOT fingerprinted as a task input, so it cannot break build
// caching / up-to-date checks the way a timestamped systemProperty() would.
// The contract is pinned by ExecutionStorageTaskTest.testExecutionStorageIsIsolatedFromRealUserHome.
tasks.withType<Test>().configureEach {
    val taskName = name
    val testRunsBaseDir = layout.buildDirectory.dir("mcp-steroid-test-runs").get().asFile
    // `lazy` so the timestamp is minted once per task execution even if Gradle
    // asks the provider for its arguments more than once while forking the JVM.
    val runDir by lazy { testRunsBaseDir.resolve("$taskName-${System.currentTimeMillis()}") }
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmcp.steroid.storage.path=${runDir.absolutePath}")
    })
}

tasks {
    test {
        useJUnit()

        // Explicitly restrict to the main 'test' source set. Without this, the
        // IntelliJ Platform plugin's TestIdeTask includes instrumented classes from
        // ALL test-like source sets (including 'integrationTest'), causing Docker CLI
        // tests to run during the regular :ij-plugin:test task where they fail due
        // to missing API keys.
        testClassesDirs = sourceSets["test"].output.classesDirs

        // Feed the project-resolved kotlinx pins to KotlinxBundledVersionTest so
        // the test compares the IDE-bundled versions to the ACTUAL project pins
        // (not to hardcoded constants that could silently drift out of sync).
        // Values come from root gradle.properties (mcp.kotlinx.*.version), so
        // a paired bump is a one-line edit shared by all six implementation modules.
        systemProperty(
            "mcp.steroid.test.expected.kotlinxCoroutinesVersion",
            providers.gradleProperty("mcp.kotlinx.coroutines.version").get(),
        )
        systemProperty(
            "mcp.steroid.test.expected.kotlinxSerializationVersion",
            providers.gradleProperty("mcp.kotlinx.serialization.version").get(),
        )
    }

    patchPluginXml {
        if (isReleaseBuild) {
            // Release builds require release notes — track the file as a
            // mandatory input so changes trigger a rebuild.
            inputs.file(releaseNotesFile)
        }
    }
}

val verifyBundledKotlinCompatibility = tasks.register<VerifyBundledKotlinCompatibilityTask>("verifyBundledKotlinCompatibility") {
    group = "verification"
    description = "Verify bundled kotlinc is close enough to IntelliJ-bundled kotlin-stdlib"
    dependsOn(kotlincDist)
    dependsOn(tasks.prepareSandbox)

    val sourceSets = project.extensions.getByType<SourceSetContainer>()
    mainRuntimeClasspath.from(sourceSets.getByName("main").runtimeClasspath)
    mainRuntimeClasspath.from(configurations.getByName("intellijPlatformDependency"))
    kotlincHome.set(kotlincDist.elements.map { files ->
        val dir = files.first().asFile.resolve("kotlinc")
        layout.projectDirectory.dir(dir.absolutePath)
    })
    kotlinPluginVersion.set(providers.provider {
        plugins.getPlugin(org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper::class.java).pluginVersion
    })
    reportFile.set(layout.buildDirectory.file("reports/kotlin-version-compatibility.txt"))
}

// Runtime classloader probe: launches a JVM with classpath = IDE lib jars +
// the :intellij-downloader jar (KotlinxRuntimeProbe.main + @Serializable
// companions). The stowaway-jar guard inside the task rejects any external
// `kotlinx-*` on the probe classpath — the kotlinx-coroutines /
// kotlinx-serialization / kotlinx-io / etc. runtime MUST come from the IDE
// bundle. A LinkageError in the forked JVM fails the build at :check time
// (wired below) and at :verifyPlugin time.
//
// One sub-task per verifierTargets entry, so 262 EAP is exercised alongside 261.
val verifyBundledKotlinxRuntimeTasks = McpSteroidIdeTargets.verifierTargets.map { target ->
    tasks.register("verifyBundledKotlinxRuntime${target.major}", VerifyBundledKotlinxRuntimeTask::class) {
        group = "verification"
        description = "Run KotlinxRuntimeProbe against IDE ${target.major} (${target.version}) " +
            "to validate kotlinx-coroutines / serialization / io link compat"

        ideRoot.set(layout.dir(provider { ideRootFor(target) }))
        probeClasspath.from(project(":intellij-downloader").tasks.named("jar"))
        probeMainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.KotlinxRuntimeProbe")
        probeArgs.set(listOf(target.major, target.version))
        reportFile.set(layout.buildDirectory.file("reports/kotlinx-runtime-probe-${target.major}.txt"))
    }
}

// Umbrella so other tasks depend on "all verifier targets verified" with a single
// reference. Required by tasks.check + tasks.verifyPlugin below.
val verifyBundledKotlinxRuntime = tasks.register("verifyBundledKotlinxRuntime") {
    group = "verification"
    description = "Run KotlinxRuntimeProbe against every IDE in McpSteroidIdeTargets.verifierTargets"
    dependsOn(verifyBundledKotlinxRuntimeTasks)
}

val ocrToolDist = configurations.create("ocrToolDist") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "install-dist"))
    }
}

dependencies {
    ocrToolDist(project(":ocr-tesseract"))
}

// Apply the same plugin-content wiring (ocr-tesseract, kotlinc, EULA) to every sandbox
// that runs the plugin: production (prepareSandbox), default test (prepareTestSandbox),
// and the dedicated integrationTest sandbox created by
// `intellijPlatformTesting.testIde { register("integrationTest") }` above.
// Without the integrationTest entry, steroid_execute_code fails at runtime with
// "Kotlinc executable not found: .../plugins_integrationTest/mcp-steroid/kotlinc/bin/kotlinc".
val prepareSandbox_integrationTest = tasks.named<Sync>("prepareSandbox_integrationTest")

listOf(tasks.prepareSandbox, tasks.prepareTestSandbox, prepareSandbox_integrationTest).forEach { r ->
    r.configure {
        from(ocrToolDist) {
            into(intellijPlatform.projectName.map { "$it/ocr-tesseract" })
            filesMatching("bin/*") {
                if (!name.endsWith(".bat")) {
                    permissions { unix("rwxr-xr-x") }
                }
            }
        }
        from(kotlincDist) {
            into(intellijPlatform.projectName)
            filesMatching("kotlinc/bin/*") {
                if (!name.endsWith(".bat")) {
                    permissions { unix("rwxr-xr-x") }
                }
            }
        }
        // Include EULA file in plugin root
        from(rootProject.layout.projectDirectory.file("EULA")) {
            into(intellijPlatform.projectName)
        }
        // Include NOTICE (Apache 2.0 §4(d) third-party attribution) in plugin root
        from(rootProject.layout.projectDirectory.file("NOTICE")) {
            into(intellijPlatform.projectName)
        }
    }
}

// Expose plugin .zip for consumption by test-integration module
val pluginZipElements = configurations.create("pluginZipElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

tasks.buildPlugin {
    // Preserve executable permissions in the ZIP for scripts
    filesMatching("**/kotlinc/bin/*") {
        if (!name.endsWith(".bat")) {
            permissions { unix("rwxr-xr-x") }
        }
    }
    filesMatching("**/ocr-tesseract/bin/*") {
        if (!name.endsWith(".bat")) {
            permissions { unix("rwxr-xr-x") }
        }
    }

    doFirst {
        val outputDir = tasks.buildPlugin.get().archiveFile.get().asFile.parentFile
        val zips = outputDir
            .listFiles()
            ?.filter { it.name.endsWith(".zip") }
            ?.filter { file -> file.lastModified() < System.currentTimeMillis() - 1000L * 60 * 60 * 12 }
            ?: return@doFirst

        delete(zips)
    }
}

artifacts {
    add(pluginZipElements.name, tasks.buildPlugin.map { it.archiveFile }) {
        builtBy(tasks.buildPlugin)
    }
}

// Verify bundled libraries in plugin/lib folder
val pluginVersion = version.toString()
val verifyBundledLibraries = tasks.register("verifyBundledLibraries") {
    group = "verification"
    description = "List and verify libraries bundled in plugin lib folder"
    dependsOn(tasks.buildPlugin)
    doLast {
        val zip = tasks.buildPlugin.get().outputs.files.singleFile

        // Read ZIP entries with executable flag detection (:X suffix)
        var allFiles: SortedSet<String> = run {
            val allFiles = mutableListOf<String>()
            zipTree(zip).visit {
                if (!isDirectory) {
                    val isExec = permissions.user.execute
                    val path = relativePath.pathString

                    allFiles += when {
                        isExec ->  "$path:X"
                        else -> path
                    }
                }
            }
            allFiles
        }.toSortedSet()

        val pluginPrefix = intellijPlatform.projectName.get() + "/"
        check(allFiles.all { it.startsWith(pluginPrefix) }) {
            "files must be under plugin roots: " + allFiles.map { it.substringBefore('/') }.toSortedSet()
        }
        allFiles = allFiles.map { it.removePrefix(pluginPrefix) }.toSortedSet()

        check(allFiles.isNotEmpty()) { "no libraries found in ${allFiles.joinToString { "\n  - $it" }}" }

        val kotlincFiles = allFiles.filter { it.startsWith("kotlinc/") }.toSortedSet()
        check(kotlincFiles.contains("kotlinc/bin/kotlinc:X")) { "Kotlinc must be included in " + kotlincFiles.joinToString { "\n $it" } }
        check(kotlincFiles.contains("kotlinc/bin/kotlinc.bat")) { "Kotlinc must be included in " + kotlincFiles.joinToString { "\n $it" } }
        allFiles = (allFiles - kotlincFiles).toCollection(sortedSetOf())


        val ocrFiles = allFiles.filter { it.startsWith("ocr-tesseract/") }.toSortedSet()
        check(ocrFiles.contains("ocr-tesseract/bin/ocr-tesseract:X")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.contains("ocr-tesseract/bin/ocr-tesseract.bat")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.contains("ocr-tesseract/tessdata/eng.traineddata")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.contains("ocr-tesseract/tessdata/osd.traineddata")) { "ocr-tesseract must be included in " + ocrFiles.joinToString { "\n $it" } }
        check(ocrFiles.any { it.startsWith("ocr-tesseract/lib/ocr-common-$pluginVersion.jar") }) { "ocr-common jar must be included in ocr-tesseract" }
        check(ocrFiles.any { it.startsWith("ocr-tesseract/lib/ocr-tesseract-$pluginVersion.jar") }) { "ocr-tesseract jar must be included in ocr-tesseract" }

        allFiles = (allFiles - ocrFiles).toCollection(sortedSetOf())

        // Assert expected libraries - update this list when dependencies change
        val expectedFiles = sortedSetOf(
            // EULA file
            "EULA",
            // Apache 2.0 §4(d) third-party attribution notices
            "NOTICE",

            //our binaires
            "lib/ai-agents-$pluginVersion.jar",
            "lib/ij-plugin-$pluginVersion.jar",
            "lib/kotlin-cli-$pluginVersion.jar",
            "lib/ocr-common-$pluginVersion.jar",
            "lib/execution-storage-$pluginVersion.jar",
            "lib/mcp-core-$pluginVersion.jar",
            "lib/mcp-http-$pluginVersion.jar",
            "lib/mcp-steroid-server-$pluginVersion.jar",
            "lib/prompts-api-$pluginVersion.jar",
            "lib/prompts-$pluginVersion.jar",

            //libraries
            "lib/config-1.4.5.jar",
            "lib/gson-2.10.1.jar",
            "lib/jansi-2.4.2.jar",

            "lib/ktor-events-jvm-3.3.2.jar",
            "lib/ktor-http-cio-jvm-3.3.2.jar",
            "lib/ktor-http-jvm-3.3.2.jar",
            "lib/ktor-io-jvm-3.3.2.jar",
            "lib/ktor-network-jvm-3.3.2.jar",
            "lib/ktor-serialization-jvm-3.3.2.jar",
            "lib/ktor-server-cio-jvm-3.3.2.jar",
            "lib/ktor-server-core-jvm-3.3.2.jar",
            "lib/ktor-server-sse-jvm-3.3.2.jar",
            "lib/ktor-sse-jvm-3.3.2.jar",
            "lib/ktor-utils-jvm-3.3.2.jar",
            "lib/ktor-websockets-jvm-3.3.2.jar",

            "lib/okhttp-4.11.0.jar",
            "lib/okio-jvm-3.2.0.jar",
            "lib/posthog-6.4.0.jar",
            "lib/posthog-server-2.3.0.jar",

        ).toSortedSet()

        if (allFiles != expectedFiles) {
            val missing = expectedFiles - allFiles
            val unexpected = allFiles - expectedFiles
            throw GradleException(buildString {
                appendLine("Bundled libraries mismatch!")
                if (missing.isNotEmpty()) {
                    appendLine("Missing libraries:")
                    missing.forEach { appendLine("  - $it") }
                }
                if (unexpected.isNotEmpty()) {
                    appendLine("Unexpected libraries:")
                    unexpected.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Actual libraries: ")
                allFiles.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Update expectedLibraries in build.gradle.kts if this change is intentional.")
            })
        }
    }
}

// Class-file version guard: every class the plugin bundles must fit the OLDEST JBR the plugin targets, or
// it throws UnsupportedClassVersionError at load time. Android Studio on platform 261 bundles JBR 21
// (class-file major 65) where IntelliJ IDEA 2026.1 bundles JBR 25 (major 69) — so a JDK-25 toolchain that
// emits major-69 classes loads in IDEA but NOT in Android Studio. Scans every bundled jar/zip recursively
// at any folder (including kotlinc / ocr-tesseract). `maxJavaFeature` is the single knob the JDK-target
// change lowers to 21.
val verifyClassFileVersions = tasks.register<VerifyClassFileVersionTask>("verifyClassFileVersions") {
    group = "verification"
    description = "Verify bundled plugin class files load on the oldest supported JBR (class-file version guard)"
    archives.from(tasks.buildPlugin)
    maxJavaFeature.set(21)
}

tasks.test {
    dependsOn(verifyBundledLibraries)
    dependsOn(verifyClassFileVersions)
}

// Wire the kotlinx runtime probe into :check so local `./gradlew :ij-plugin:check`
// catches drift; :verifyPlugin keeps the dependency too for the full CI path.
tasks.check {
    dependsOn(verifyBundledKotlinxRuntime)
}

tasks.buildPlugin {
    finalizedBy(verifyBundledLibraries)
    finalizedBy(verifyClassFileVersions)
}

tasks.verifyPlugin {
    dependsOn(verifyBundledKotlinCompatibility)
    dependsOn(verifyBundledKotlinxRuntime)
    dependsOn(verifyBundledLibraries)
    dependsOn(verifyClassFileVersions)
}

// Deploy plugin to running IDEs with hot-reload support
val deployPlugin = tasks.register("deployPlugin") {
    group = "intellij platform"
    description = "Deploy plugin to running IDEs"
    dependsOn(verifyBundledLibraries)
    doLast {
        val zip = tasks.buildPlugin.get().outputs.files.singleFile
        val home = File(System.getProperty("user.home"))
        val endpoints = home.listFiles { f -> f.name.matches(Regex("\\.\\d+\\.hot-reload")) }
            ?.mapNotNull { f ->
                val pid = Regex("\\.(\\d+)\\.").find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                if (!ProcessHandle.of(pid).isPresent) return@mapNotNull null
                val lines = f.readLines().takeIf { it.size >= 2 } ?: return@mapNotNull null
                lines[0] to lines[1]
            }?.distinctBy { it.first } ?: emptyList()

        require(endpoints.isNotEmpty()) { "No running IDEs found" }

        endpoints.forEach { (url, token) ->
            val encodedPath = URLEncoder.encode(zip.absolutePath, Charsets.UTF_8)
            val fileUrl = "$url?local-disk-file=$encodedPath"
            println("\n→ $fileUrl")
            val conn = (URI(fileUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = false; doInput = true
                setRequestProperty("Authorization", token)
                connectTimeout = 5000; readTimeout = 300000
            }
            val responseLines = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readLines()
            } else {
                conn.errorStream?.bufferedReader()?.readLines().orEmpty()
            }
            responseLines.forEach { println("  $it") }
            require(conn.responseCode in 200..299 && responseLines.lastOrNull() == "SUCCESS") {
                "Deploy failed (HTTP ${conn.responseCode}): ${responseLines.lastOrNull()}"
            }
        }
    }
}


val deployPluginLocallyTo253 = tasks.register<Sync>("deployPluginLocallyTo253") {
    dependsOn(tasks.buildPlugin)
    dependsOn(verifyBundledLibraries)
    group = "intellij platform"
    outputs.upToDateWhen { false }

    val targetName = "" + rootProject.name
    val targetDir = "${System.getenv("HOME")}/intellij-253/config/plugins/$targetName"

    this.destinationDir = file(targetDir)
    from(
        tasks.buildPlugin
            .map { it.archiveFile }
            .map { zipTree(it) }
    ) {
        includeEmptyDirs = false
        eachFile {
            println(this)
            this.path = this.path.substringAfter("/")
        }
    }
}

/**
 * Cold-deploy the built plugin into the standalone IntelliJ IDEA 2026.1
 * (build 261) install's plugin dir on macOS:
 * `~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/mcp-steroid`.
 *
 * Use case: the 262 EAP work bumped `sinceBuild` (253 → 261) + Kotlin (2.2.x
 * → 2.3.20) + the JVM toolchain (21 → 25), which is too deep a change for
 * Plugin Hot Reload (`:ij-plugin:deployPlugin`) to swap in place. This Sync
 * task drops the unpacked plugin into the 261 config dir; the user then
 * restarts the IDE to pick it up.
 *
 * Mirrors `deployPluginLocallyTo253` (kept as-is per user direction for
 * the legacy sandbox install). When the work stream eventually bumps
 * `sinceBuild` to 262, add a parallel `deployPluginLocallyTo262` task —
 * the pattern is local-machine-specific by design (hardcoded folder
 * names per user U9 direction).
 */
val deployPluginLocallyTo261 = tasks.register<Sync>("deployPluginLocallyTo261") {
    dependsOn(tasks.buildPlugin)
    dependsOn(verifyBundledLibraries)
    group = "intellij platform"
    outputs.upToDateWhen { false }

    val targetName = "" + rootProject.name
    val targetDir = "${System.getenv("HOME")}/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/$targetName"

    this.destinationDir = file(targetDir)
    from(
        tasks.buildPlugin
            .map { it.archiveFile }
            .map { zipTree(it) }
    ) {
        includeEmptyDirs = false
        eachFile {
            println(this)
            this.path = this.path.substringAfter("/")
        }
    }
}

/**
 * Cold-deploy the built plugin into the user's main IDE sandbox at
 * `~/.intellij-main/config/plugins/mcp-steroid`. This is the non-default
 * `idea.config.path` the user runs the day-to-day IDEA 2026.1 instance
 * under (set via JVM flags in the IDE's vmoptions). The standard
 * `~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/`
 * dir is unused — the user-customised path wins. Discovered when
 * `deployPluginLocallyTo261` staged the new plugin but the running IDE
 * (PID 4298 after restart) kept reporting the old 0.95.0 version: the
 * default-path stage doesn't influence the custom-path IDE.
 *
 * Same shape as `deployPluginLocallyTo253` and `deployPluginLocallyTo261`
 * (local-machine-specific by design — hardcoded folder per user U9
 * direction). When this sandbox eventually moves elsewhere, edit the
 * `targetDir` literal, not the task structure.
 */
val deployPluginLocallyToIntelliJMain = tasks.register<Sync>("deployPluginLocallyToIntelliJMain") {
    dependsOn(tasks.buildPlugin)
    dependsOn(verifyBundledLibraries)
    group = "intellij platform"
    outputs.upToDateWhen { false }

    val targetName = "" + rootProject.name
    val targetDir = "${System.getenv("HOME")}/.intellij-main/config/plugins/$targetName"

    this.destinationDir = file(targetDir)
    from(
        tasks.buildPlugin
            .map { it.archiveFile }
            .map { zipTree(it) }
    ) {
        includeEmptyDirs = false
        eachFile {
            println(this)
            this.path = this.path.substringAfter("/")
        }
    }
}
