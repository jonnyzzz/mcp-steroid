/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.koltinc.CodeWrapperForCompilation
import com.jonnyzzz.mcpSteroid.koltinc.KotlincCommandLineBuilder
import com.jonnyzzz.mcpSteroid.koltinc.toArgFile
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import org.junit.jupiter.api.Assertions
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk

/**
 * Base class for generated KtBlock compilation tests (JUnit 5).
 *
 * Compiles Kotlin code blocks from prompt articles against the full IDE classpath
 * using an external kotlinc process. The IDE home, kotlinc home, and ij-plugin source
 * directory are resolved from system properties set by the Gradle build.
 *
 * The wrapper code references `McpScriptContext` and `McpScriptBuilder` (matching
 * the real `CodeButcher` output). Their source files from ij-plugin are compiled
 * together with the wrapped test code.
 *
 * System properties:
 * - `mcp.steroid.ide.home` — path to the unpacked IDE distribution
 * - `mcp.steroid.kotlinc.home` — path to the unpacked kotlinc distribution (parent of `kotlinc/`)
 * - `mcp.steroid.ij.sources` — path to ij-plugin/src/main/kotlin (for McpScriptContext/McpScriptBuilder sources)
 * - `mcp.steroid.ktblock.cache.dir` — path to compilation cache directory (optional but recommended)
 */
abstract class KtBlockCompilationTestBase {

    /** Compiles a Kotlin code block against the IntelliJ IDEA classpath. */
    protected fun compileKtBlockOnIdea(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.ide.home", werror = true)
    }

    /** Compiles a Kotlin code block against the Rider classpath. */
    protected fun compileKtBlockOnRider(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.rider.home", werror = true)
    }

    /** Compiles a Kotlin code block against the CLion classpath. */
    protected fun compileKtBlockOnClion(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.clion.home", werror = true)
    }

    /** Compiles a Kotlin code block against the PyCharm classpath. */
    protected fun compileKtBlockOnPycharm(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.pycharm.home", werror = true)
    }

    /** Compiles a Kotlin code block against the IntelliJ IDEA EAP classpath (warnings allowed). */
    protected fun compileKtBlockOnIdeaEap(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.ide.eap.home", werror = false)
    }

    /** Compiles a Kotlin code block against the Rider EAP classpath (warnings allowed). */
    protected fun compileKtBlockOnRiderEap(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.rider.eap.home", werror = false)
    }

    /** Compiles a Kotlin code block against the CLion EAP classpath (warnings allowed). */
    protected fun compileKtBlockOnClionEap(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.clion.eap.home", werror = false)
    }

    /** Compiles a Kotlin code block against the PyCharm EAP classpath (warnings allowed). */
    protected fun compileKtBlockOnPycharmEap(block: PromptBase) {
        compileAgainst(block, "mcp.steroid.pycharm.eap.home", werror = false)
    }

    private fun compileAgainst(block: PromptBase, homeProperty: String, werror: Boolean) {
        val home = System.getProperty(homeProperty)
        if (home == null) {
            // On TeamCity, skip tests for IDEs that weren't provided — each IDE group
            // runs in its own build configuration with only its specific IDE downloaded.
            // Locally, all IDEs are expected to be present.
            if (System.getenv("TEAMCITY_VERSION") != null) {
                throw org.opentest4j.TestAbortedException(
                    "IDE not available (system property '$homeProperty' not set) — skipping on CI"
                )
            }
            error("Missing system property '$homeProperty' — IDE distribution not available")
        }

        assertTestJreMatchesIdeBundled(home, homeProperty)

        val content = block.readPrompt()
        val wrapped = CodeWrapperForCompilation.wrap("MdKtBlock", content).code
        val homePath = Path.of(home)
        val classpath = classpathFor(home) + extraClasspath()
        val extraSourcesContent = ijPluginSourceFiles().map { Files.readString(it, StandardCharsets.UTF_8) }

        // Compiler options (must match what KotlincCommandLineBuilder produces)
        val compilerOptions = buildList {
            if (werror) add("-Werror")
            add("-jvm-target")
            add(KotlincCommandLineBuilder.DEFAULT_JVM_TARGET)
            add("-no-stdlib")
        }

        // product-info.json content for IDE identity
        val productInfoContent = readProductInfo(home)

        // kotlinc version for compiler identity
        val kotlincVersion = readKotlincVersion()

        // Relative classpath paths (relative to IDE home) — avoids machine-specific absolute paths in hash
        val relativeClasspath = classpath.map { homePath.relativize(it).toString() }

        // Check compilation cache
        val cacheDir = cacheDir()
        val cacheKey = computeCacheKey(wrapped, relativeClasspath, compilerOptions, productInfoContent, extraSourcesContent, kotlincVersion)

        val cacheFile = cacheDir.resolve("$cacheKey.txt")
        if (cacheFile.isFile) {
            println("[cache hit] $cacheKey")
            return
        }

        val tempDir = Files.createTempDirectory("md-kt-block-compile")
        try {
            val sourceFile = tempDir.resolve("Script.kt")
            Files.writeString(sourceFile, wrapped, StandardCharsets.UTF_8)
            val outputJar = tempDir.resolve("out.jar")
            val extraParams = if (werror) listOf("-Werror") else emptyList()
            val builder = KotlincCommandLineBuilder(outputJar)
                .withNoStdLib(true)
                .withExtraParameters(extraParams)
                .addClasspathEntries(classpath)
                .addSource(sourceFile)

            for (sourceExtra in ijPluginSourceFiles()) {
                builder.addSource(sourceExtra)
            }

            val cmd = builder.build()
            val argFile = tempDir.resolve("kotlinc.args")
            val argCmd = cmd.toArgFile(argFile)

            val kotlincBin = resolveKotlincBin()
            val result = RunProcessRequest(
                workingDir = tempDir.toFile(),
                args = listOf(kotlincBin.absolutePath) + argCmd.args,
                logPrefix = "kotlinc",
                timeout = Duration.ofMinutes(3),
                quietly = false,
            ).startProcess().assertExitCode(0) {
                buildString {
                    appendLine("Compilation failed or has warnings (-Werror) [IDE: $homeProperty]:")
                    if (stdout.isNotBlank()) {
                        appendLine("STDOUT:")
                        appendLine(stdout)
                    }
                    if (stderr.isNotBlank()) {
                        appendLine("STDERR:")
                        appendLine(stderr)
                    }
                }
            }

            // Cache successful compilation
            writeCacheEntry(cacheDir, cacheKey, wrapped, compilerOptions)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    companion object {
        private val classpathCache = mutableMapOf<String, List<Path>>()

        /**
         * Verifies the JRE running this test has the same major version as the
         * JBR bundled inside [home]. The kotlinc subprocess derives `-jvm-target`
         * from `java.specification.version` ([KotlincCommandLineBuilder.DEFAULT_JVM_TARGET]),
         * so that target must equal the IDE's bundled JBR major — otherwise the
         * IDE's inline bytecode (compiled against its bundled JBR) will be
         * rejected by kotlinc (`cannot inline bytecode built with JVM target N
         * into bytecode that is being built with JVM target M`).
         *
         * Ideally we'd run the test under the IDE's bundled JBR directly, but
         * downloaded IDE distributions are Linux-only — on a macOS dev machine
         * Gradle can't launch `<linux-ide>/jbr/bin/java`. So we use Gradle's
         * `javaLauncher` toolchain (see `prompts/build.gradle.kts`) to pin the
         * test JVM to the matching major version, and this assertion is the
         * canary that flags drift between the toolchain pin and what the IDE
         * actually ships.
         *
         * On mismatch: bump the toolchain in `prompts/build.gradle.kts` to the
         * major version reported by the IDE's `jbr/release` file.
         */
        private fun assertTestJreMatchesIdeBundled(home: String, homeProperty: String) {
            val bundledMajor = readIdeBundledJreMajor(home)
            val testMajor = System.getProperty("java.specification.version")
                ?: error("System property 'java.specification.version' is not set on the test JVM")
            Assertions.assertEquals(
                bundledMajor, testMajor,
                "Test JRE major version ($testMajor) does not match the IDE-bundled JBR " +
                    "major version ($bundledMajor) at [$homeProperty]=$home. The kotlinc " +
                    "`-jvm-target` for these tests is derived from `java.specification.version`, " +
                    "so the test JVM must match the IDE's bundled JBR major to compile against " +
                    "its inline bytecode. Bump `tasks.test.javaLauncher` in `prompts/build.gradle.kts` " +
                    "to JDK $bundledMajor (or run the test with a matching JRE)."
            )
        }

        private val ideBundledJreMajorCache = mutableMapOf<String, String>()

        private fun readIdeBundledJreMajor(home: String): String {
            return ideBundledJreMajorCache.getOrPut(home) {
                val releaseFile = Path.of(home, "jbr", "release")
                require(releaseFile.isRegularFile()) {
                    "IDE-bundled JBR release file not found: $releaseFile"
                }
                val content = Files.readString(releaseFile, StandardCharsets.UTF_8)
                val javaVersionLine = content.lines().firstOrNull { it.startsWith("JAVA_VERSION=") }
                    ?: error("JAVA_VERSION line not found in $releaseFile")
                // `JAVA_VERSION="25.0.2"` → major `"25"`. `java.specification.version`
                // is the bare major (`"25"`), so we strip both the quotes and the patch suffix.
                javaVersionLine.substringAfter('=').trim().trim('"').substringBefore('.')
            }
        }

        private fun classpathFor(home: String): List<Path> {
            return classpathCache.getOrPut(home) {
                Path.of(home)
                    .walk()
                    .filter { it.isRegularFile() && it.name.endsWith(".jar") }
                    .toList()
            }
        }

        /**
         * Extra binary classpath entries the per-block kotlinc subprocess needs
         * because the inlined ij-plugin sources reference classes that live in
         * sibling project modules — not in any IDE-bundled jar. Today the only
         * such reference is `ApplyPatchHunk` in `:mcp-steroid-server` (imported
         * from `ApplyPatch.kt`, which the test inlines). Populated by Gradle:
         * see `prompts/build.gradle.kts` → `ktblockExtraClasspath` configuration
         * and the `mcp.steroid.extra.classpath` system property in
         * `tasks.test.doFirst`. Empty if the property is unset, which keeps
         * the test runnable from IDE configurations that don't go through Gradle.
         */
        private val extraClasspathCache: List<Path> by lazy {
            val raw = System.getProperty("mcp.steroid.extra.classpath").orEmpty()
            if (raw.isBlank()) emptyList()
            else raw.split(File.pathSeparator).filter { it.isNotBlank() }.map(Path::of)
        }

        private fun extraClasspath(): List<Path> = extraClasspathCache

        private val ijPluginSourceFilesCache: List<Path> by lazy {
            val ijSourcesDir = System.getProperty("mcp.steroid.ij.sources")
                ?: error("Missing system property 'mcp.steroid.ij.sources'")
            val executionDir = Path.of(ijSourcesDir, "com", "jonnyzzz", "mcpSteroid", "execution")
            // ApplyPatch.kt defines ApplyPatchBuilder / ApplyPatchResult / ApplyPatchException,
            // which McpScriptContext.kt references at its `applyPatch { }` extension — must be
            // supplied to kotlinc alongside so fenced-block scripts that use the DSL compile.
            listOf("McpScriptContext.kt", "McpScriptBuilder.kt", "ApplyPatch.kt").map { fileName ->
                val file = executionDir.resolve(fileName)
                require(file.isRegularFile()) { "ij-plugin source file not found: $file" }
                file
            }
        }

        private fun ijPluginSourceFiles(): List<Path> = ijPluginSourceFilesCache

        private fun resolveKotlincBin(): File {
            val kotlincHome = System.getProperty("mcp.steroid.kotlinc.home")
                ?: error("Missing system property 'mcp.steroid.kotlinc.home'")
            val kotlincDir = File(kotlincHome, "kotlinc")
            val bin = File(kotlincDir, "bin/kotlinc")
            require(bin.isFile) { "kotlinc binary not found at: $bin" }
            return bin
        }

        private val kotlincVersionCache: String by lazy {
            val kotlincHome = System.getProperty("mcp.steroid.kotlinc.home")
                ?: error("Missing system property 'mcp.steroid.kotlinc.home'")
            val buildTxt = File(kotlincHome, "kotlinc/build.txt")
            require(buildTxt.isFile) { "kotlinc build.txt not found at: $buildTxt" }
            buildTxt.readText(StandardCharsets.UTF_8).trim()
        }

        private fun readKotlincVersion(): String = kotlincVersionCache

        private val productInfoCache = mutableMapOf<String, String>()

        private fun readProductInfo(home: String): String {
            return productInfoCache.getOrPut(home) {
                val productInfoFile = Path.of(home, "product-info.json")
                require(productInfoFile.isRegularFile()) {
                    "product-info.json not found in IDE home: $productInfoFile"
                }
                Files.readString(productInfoFile, StandardCharsets.UTF_8)
            }
        }

        private fun cacheDir(): File {
            val dir = System.getProperty("mcp.steroid.ktblock.cache.dir")
                ?: error("Missing system property 'mcp.steroid.ktblock.cache.dir'")
            val file = File(dir)
            file.mkdirs()
            require(file.isDirectory) { "Cache directory does not exist and could not be created: $dir" }
            return file
        }

        /**
         * Computes a SHA-512 hash from all inputs that affect compilation outcome.
         * The hash is deterministic for the same inputs.
         */
        private fun computeCacheKey(
            wrappedSource: String,
            relativeClasspath: List<String>,
            compilerOptions: List<String>,
            productInfoContent: String,
            extraSourcesContent: List<String>,
            kotlincVersion: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-512")

            fun feedString(s: String) {
                digest.update(s.toByteArray(StandardCharsets.UTF_8))
                digest.update(0) // null terminator as separator
            }

            feedString("source:")
            feedString(wrappedSource)

            feedString("classpath:")
            for (entry in relativeClasspath.sorted()) {
                feedString(entry)
            }

            feedString("options:")
            for (option in compilerOptions) {
                feedString(option)
            }

            feedString("product-info:")
            feedString(productInfoContent)

            feedString("extra-sources:")
            for (content in extraSourcesContent) {
                feedString(content)
            }

            feedString("kotlinc-version:")
            feedString(kotlincVersion)

            val hashBytes = digest.digest()
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Writes a cache entry for a successful compilation.
         * Includes timestamp, source, and compiler args for debugging.
         */
        private fun writeCacheEntry(cacheDir: File, cacheKey: String, source: String, compilerOptions: List<String>) {
            val cacheFile = File(cacheDir, "$cacheKey.txt")
            cacheFile.writeText(buildString {
                appendLine("# KtBlock compilation cache entry")
                appendLine("# timestamp: ${Instant.now()}")
                appendLine("# kotlinc-version: ${readKotlincVersion()}")
                appendLine("# compiler-options: ${compilerOptions.joinToString(" ")}")
                appendLine("#")
                appendLine("# source:")
                for (line in source.lines()) {
                    appendLine("# $line")
                }
            })
        }
    }
}
