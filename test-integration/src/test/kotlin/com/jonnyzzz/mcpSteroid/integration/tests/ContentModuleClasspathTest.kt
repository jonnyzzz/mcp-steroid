package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Verifies that classes from IntelliJ plugin content modules are on the
 * kotlinc compile classpath. In production IDEs (2025.3+), plugins are split
 * into content modules with separate classloaders. If ideClasspath() only
 * collects JARs from main plugin descriptors, imports like
 * AnnotatedElementsSearch fail with "unresolved reference" at compile time.
 *
 * See https://github.com/jonnyzzz/mcp-steroid/issues/16
 *
 * Run with IntelliJ IDEA 2025.3 (stable):
 *   ./gradlew :test-integration:test --tests '*ContentModuleClasspathTest*'
 *
 * Run with IntelliJ IDEA 2026.1 (EAP):
 *   ./gradlew :test-integration:test --tests '*ContentModuleClasspathTest*' -Dtest.integration.ide.channel=eap
 */
class ContentModuleClasspathTest {
    companion object {
        val lifetime by lazy { CloseableStackHost(this::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(
                lifetime, IntelliJContainerOpts(
                    consoleTitle = "Content Module Classpath"
                )
            )
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }

        // =====================================================================
        // Unloaded content modules: plugins/*/lib/modules/*.jar files that
        // exist on disk but are NOT loaded by the plugin system at runtime.
        //
        // Each entry is listed explicitly so the test catches any change in
        // module loading behavior across IDE versions.
        //
        // When updating to a new IDE version:
        //   1. Run the test — it will fail listing unexpected JARs
        //   2. Investigate each new JAR: is it intentionally unloaded?
        //   3. Add it to the appropriate category below
        //   4. Remove any stale entries that no longer appear
        // =====================================================================

        /**
         * Unloaded content modules for IntelliJ IDEA Ultimate (IU) 2026.1.
         *
         * Organized by reason for not being loaded.
         */
        private val UNLOADED_CONTENT_MODULES_IU_261 = buildSet {
            // --- Frontend-split modules ---
            // Only loaded in thin client mode (JetBrains Gateway / remote development).
            // In full IDE mode these modules provide no functionality.
            addAll(listOf(
                "plugins/DatabaseTools/lib/modules/intellij.database.frontend.split.jar",
                "plugins/clouds-docker-impl/lib/modules/intellij.clouds.docker.file.frontend.split.jar",
                "plugins/css-plugin/lib/modules/intellij.css.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.completion.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.credentialStore.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.execution.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.identifiers.highlighting.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.inline.completion.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.kernel.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.plugins.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.progress.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.project.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.searchEverywhere.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.platform.vcs.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.vcs.git.frontend.split.jar",
                "plugins/cwm-plugin/lib/modules/intellij.xml.frontend.split.jar",
                "plugins/editorconfig-plugin/lib/modules/intellij.editorconfig.frontend.split.jar",
                "plugins/java/lib/modules/intellij.java.frontend.split.jar",
                "plugins/json/lib/modules/intellij.json.frontend.split.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.split.frontend.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.split.frontend.py.jar",
                "plugins/markdown/lib/modules/intellij.markdown.frontend.split.jar",
                "plugins/sass-plugin/lib/modules/intellij.sass.frontend.split.jar",
                "plugins/sh-plugin/lib/modules/intellij.sh.frontend.split.jar",
                "plugins/station-plugin/lib/modules/intellij.station.frontend.split.jar",
                "plugins/terminal/lib/modules/intellij.terminal.frontend.split.jar",
                "plugins/textmate-plugin/lib/modules/intellij.textmate.frontend.split.jar",
                "plugins/toml/lib/modules/intellij.toml.frontend.split.jar",
                "plugins/yaml/lib/modules/intellij.yaml.frontend.split.jar",
            ))

            // --- CWM shared module ---
            // Part of Code With Me infrastructure, loaded only in collaborative sessions.
            addAll(listOf(
                "plugins/cwm-plugin/lib/modules/intellij.platform.identifiers.highlighting.shared.jar",
            ))

            // --- File watcher modules ---
            // Loaded on demand when external file watcher tools are configured.
            addAll(listOf(
                "plugins/css-plugin/lib/modules/intellij.css.watcher.jar",
                "plugins/javascript-plugin/lib/modules/intellij.javascript.fileWatcher.jar",
                "plugins/less/lib/modules/intellij.less.watcher.jar",
                "plugins/sass-plugin/lib/modules/intellij.sass.watcher.jar",
            ))

            // --- Shared-indexes modules ---
            // Loaded on demand when the shared-indexes feature downloads/attaches prebuilt indexes
            // for a given language (e.g. Python). Not on the base classpath in a fresh IDE.
            addAll(listOf(
                "plugins/indexing-shared/lib/modules/intellij.python.sharedIndexes.jar",
            ))

            // --- AI / ML per-language completion modules ---
            // Loaded on demand per active language. The fullLine plugin provides
            // local code completion models for many languages; ML LLM modules provide
            // cloud-based completion integration. Both load lazily.
            addAll(listOf(
                "plugins/fullLine/lib/modules/intellij.fullLine.chat.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.erb.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.go.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.php.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.python.jupyter.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.python.jupyter.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.python.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.rbs.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.ruby.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.rust.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.terraform.local.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.chat.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.cpp.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.css.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.go.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.html.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.java.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.javaee.spring.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.javascript.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.jupyter.python.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.kotlin.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.php.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.python.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rbs.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.ruby.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rust.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.sql.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.vcs.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.yaml.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.yaml.javaee.spring.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.yaml.json.completion.jar",
            ))

            // --- Rider-specific modules ---
            // Bundled with IU but only loaded when running in Rider IDE.
            addAll(listOf(
                "plugins/fullLine/lib/modules/intellij.fullLine.rider.cpp.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.rider.csharp.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.rider.fsharp.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.rider.local.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.rider.shaderlab.jar",
                "plugins/fullLine/lib/modules/intellij.fullLine.rider.unrealengine.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rider.cpp.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rider.csharp.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rider.fsharp.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rider.godot.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rider.shaderlab.completion.jar",
                "plugins/fullLine/lib/modules/intellij.ml.llm.rider.unrealengine.completion.jar",
            ))

            // --- WebStorm-specific modules ---
            // Loaded only in WebStorm IDE.
            addAll(listOf(
                "plugins/javascript-debugger/lib/modules/intellij.javascript.debugger.webstorm.specific.jar",
                "plugins/javascript-plugin/lib/modules/intellij.javascript.newProject.jar",
            ))

            // --- DataSpell / Jupyter Python modules ---
            // Python-specific Jupyter modules, loaded only when Python plugin is active.
            addAll(listOf(
                "plugins/jupyter-plugin/lib/modules/intellij.dataspell.jupyter.customCells.sql.backend.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.dataspell.jupyter.customCells.sql.common.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.dataspell.jupyter.customCells.sql.frontend.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.py.dap.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.py.ift.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.py.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.py.pro.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.py.psi.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.py.wsl.jar",
                "plugins/jupyter-plugin/lib/modules/intellij.jupyter.split.backend.py.jar",
                "plugins/kotlin-jupyter-plugin/lib/modules/intellij.kotlin.jupyter.k1.jar",
            ))

            // --- Miscellaneous unloaded modules ---
            // Each has a specific reason for not being loaded at startup.
            //
            // NOTE: plugins/DatabaseTools/lib/modules/intellij.database.mcp.jar is
            // intentionally NOT listed here — it is build-gated. See
            // DATABASE_MCP_DROPPED_BUILD / isDatabaseMcpUnloaded() below: it was an
            // unloaded content module up to IU-261.22158 but was dropped from the IDE
            // distribution in IU-261.25134+, so its presence in the allowlist depends
            // on the resolved build.
            addAll(listOf(
                // Groovy Ant integration — loaded only when Ant is used
                "plugins/Groovy/lib/modules/intellij.groovy.ant.jar",
                // Spring JSF — loaded only when JSF framework is detected
                "plugins/Spring/lib/modules/intellij.spring.jsf.jar",
                // Kubernetes Go template support — loaded on demand
                "plugins/clouds-kubernetes/lib/modules/intellij.clouds.kubernetes.charts.gotpl.jar",
                // Code provenance LLM integration — loaded on demand
                "plugins/code-provenance/lib/modules/intellij.code.provenance.core.llm.jar",
                // Java promotional content — not a runtime module
                "plugins/java/lib/modules/intellij.java.promo.jar",
                // Qodana Rust support — loaded only in Qodana/CLion context
                "plugins/qodana/lib/modules/intellij.qodana.rust.jar",
                // Spring Boot OpenRewrite — loaded on demand
                "plugins/spring-boot-plugin/lib/modules/intellij.spring.boot.rewrite.jar",
                // Station AI assistant — loaded on demand
                "plugins/station-plugin/lib/modules/intellij.station.aia.jar",
                // Tailwind CSS Ruby integration — loaded only when the Ruby plugin is
                // active (e.g. in RubyMine or IU with Ruby plugin installed). IU has
                // no Ruby plugin bundled, so this module stays unloaded.
                "plugins/tailwindcss/lib/modules/intellij.tailwindcss.ruby.jar",
                // YAML Helm support — loaded when Helm files are detected
                "plugins/yaml/lib/modules/intellij.yaml.helm.jar",
            ))
        }

        /**
         * Build-gated module: plugins/DatabaseTools/lib/modules/intellij.database.mcp.jar.
         *
         * The set of unloaded content modules drifts within a single major because
         * :test-integration resolves the LATEST 261 build dynamically. Two confirmed
         * data points for this JAR:
         *   - IU-261.22158.277 (plugin-verifier lane): present on disk, NOT loaded at
         *     runtime → it IS an unloaded content module → the entry is needed.
         *   - IU-261.25134.95  (current test-integration lane): absent from the IDE
         *     filesystem (no longer bundled) → the entry would be stale.
         *
         * So the JAR is included as an expected-unloaded module only for builds BELOW
         * this threshold (the second build-number component for the 261 major).
         */
        private const val DATABASE_MCP_DROPPED_BUILD = 25134

        private const val DATABASE_MCP_MODULE =
            "plugins/DatabaseTools/lib/modules/intellij.database.mcp.jar"

        /**
         * Whether intellij.database.mcp.jar is expected as an unloaded content module
         * for the given build. True for builds below 261.25134 (e.g. .22158), false for
         * 261.25134 and newer.
         *
         * Fails CLOSED: if the build string is missing or unparseable, default to
         * INCLUDING the entry so older/unknown builds keep prior behavior (the entry
         * was unconditional before build-gating was introduced).
         *
         * BuildNumber.fromString() is deliberately not used: this is a plain host-JVM
         * JUnit module with no IntelliJ Platform on its classpath (see build.gradle.kts),
         * so com.intellij.openapi.util.BuildNumber is not available here. Parse manually.
         */
        private fun isDatabaseMcpUnloaded(ideBuild: String?): Boolean {
            // Strip an optional product prefix like "IU-" → "261.25134.95".
            val numeric = ideBuild?.substringAfterLast('-')?.trim()
            val parts = numeric?.split('.')
            val major = parts?.getOrNull(0)?.toIntOrNull()
            val build = parts?.getOrNull(1)?.toIntOrNull()
            // Fail closed: unknown/unparseable → keep the entry (prior behavior).
            if (major == null || build == null) return true
            // Only gate the 261 major; other majors keep prior (include) behavior until
            // a data point says otherwise.
            if (major != 261) return true
            return build < DATABASE_MCP_DROPPED_BUILD
        }

        /**
         * Returns the set of expected unloaded content module JARs for the given IDE
         * product and resolved build. Fails if the product has no known allowlist —
         * forces adding one explicitly.
         *
         * The set is build-aware: :test-integration resolves the latest build of a
         * major dynamically, so some entries are gated on the build number (see
         * isDatabaseMcpUnloaded). All other entries are unconditional.
         */
        private fun unloadedContentModules(ideProduct: String, ideBuild: String?): Set<String> {
            return when (ideProduct) {
                "IU" -> buildSet {
                    addAll(UNLOADED_CONTENT_MODULES_IU_261)
                    if (isDatabaseMcpUnloaded(ideBuild)) add(DATABASE_MCP_MODULE)
                }
                else -> error(
                    "No unloaded content modules allowlist for IDE product '$ideProduct'. " +
                        "Run the test, collect the list of unloaded modules, and add a new constant."
                )
            }
        }

        /**
         * JARs in these locations are NOT loaded by plugin classloaders by design.
         * They are auxiliary files (build tools, runtimes, compilers, resources)
         * shipped alongside plugins but executed in separate processes or contexts.
         */
        private fun isStructuralException(path: String): Boolean {
            // Rule 1: JBR (JetBrains Runtime) — bundled JDK, separate from plugin system
            if (path.startsWith("jbr/")) return true

            // Rule 2: Platform lib subdirectories — auxiliary runtime JARs
            //   lib/<subfolder>/<file>.jar (e.g., lib/rt/servlet.jar, lib/ext/platform-main.jar)
            //   These are NOT loaded by plugin classloaders.
            //   Direct lib/*.jar entries (lib/platform-api.jar etc.) ARE loaded — not excluded here.
            if (path.startsWith("lib/") && path.removePrefix("lib/").contains("/")) return true
            // lib/nio-fs.jar — standalone NIO filesystem provider, not a plugin
            if (path == "lib/nio-fs.jar") return true
            // modules/ — module descriptors metadata, not plugin JARs
            if (path.startsWith("modules/")) return true

            if (path.startsWith("plugins/")) {
                val afterPlugins = path.removePrefix("plugins/")
                val parts = afterPlugins.split("/")
                // parts: [pluginName, secondDir, ...]

                if (parts.size >= 3) {
                    // Rule 3: Plugin non-lib directories
                    //   e.g., plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar
                    //   These are standalone tool distributions, not loaded by plugin classloaders.
                    if (parts[1] != "lib") return true

                    // Rule 4: Plugin lib subdirectories OTHER THAN modules/
                    //   e.g., plugins/gradle-plugin/lib/ant/*.jar
                    //        plugins/maven/lib/maven3/lib/*.jar
                    //        plugins/java/lib/rt/debugger-agent.jar
                    //        plugins/java/lib/ecj/eclipse.jar
                    //        plugins/java/lib/resources/jdkAnnotations.jar
                    //   These are auxiliary JARs (Ant, Maven, runtime agents, ECJ compiler,
                    //   resource bundles) executed in separate processes.
                    //   plugins/*/lib/modules/*.jar are content modules — handled separately.
                    if (parts.size >= 4 && parts[1] == "lib" && parts[2] != "modules") return true
                }
            }

            return false
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `ideClasspath covers all JARs under IDE installation folder`() {
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.application.ApplicationInfo
                import com.intellij.openapi.application.PathManager
                import com.intellij.openapi.components.service
                import com.jonnyzzz.mcpSteroid.koltinc.ScriptClassLoaderFactory
                import java.nio.file.Files
                import java.nio.file.Path
                import kotlin.io.path.extension

                val info = ApplicationInfo.getInstance()
                val ideHome = Path.of(PathManager.getHomePath())
                println("IDE_HOME: ${'$'}ideHome")
                println("IDE_BUILD: ${'$'}{info.build.asString()}")
                println("IDE_PRODUCT: ${'$'}{info.build.productCode}")

                val factory = service<ScriptClassLoaderFactory>()
                val classpathJars = factory.ideClasspath()
                    .filter { it.startsWith(ideHome) }
                    .map { ideHome.relativize(it).toString() }
                    .toSortedSet()

                val filesystemJars = Files.walk(ideHome).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && it.extension == "jar" }
                        .map { ideHome.relativize(it).toString() }
                        .toList()
                        .toSortedSet()
                }

                val onlyInClasspath = (classpathJars - filesystemJars).sorted()
                val onlyInFilesystem = (filesystemJars - classpathJars).sorted()

                println("=== ONLY_IN_CLASSPATH: ${'$'}{onlyInClasspath.size} ===")
                onlyInClasspath.forEach { println("  ${'$'}it") }

                println("=== ONLY_IN_FILESYSTEM: ${'$'}{onlyInFilesystem.size} ===")
                onlyInFilesystem.forEach { println("  ${'$'}it") }

                println("=== SUMMARY ===")
                println("Classpath JARs (IDE home): ${'$'}{classpathJars.size}")
                println("Filesystem JARs (IDE home): ${'$'}{filesystemJars.size}")
                println("Only in classpath: ${'$'}{onlyInClasspath.size}")
                println("Only in filesystem: ${'$'}{onlyInFilesystem.size}")
            """.trimIndent(),
            taskId = "classpath-vs-filesystem",
            reason = "Compare ideClasspath() JARs with JARs under IDE installation folder",
        ).assertExitCode(0)

        val stdout = result.stdout
        val ideProduct = extractValue(stdout, "IDE_PRODUCT")
        val ideBuild = extractValue(stdout, "IDE_BUILD")
        println("IDE: $ideProduct, build: $ideBuild")

        val onlyInClasspath = extractSection(stdout, "ONLY_IN_CLASSPATH")
        val onlyInFilesystem = extractSection(stdout, "ONLY_IN_FILESYSTEM")

        // Classpath entries pointing to non-existent files indicate stale references
        Assertions.assertTrue(onlyInClasspath.isEmpty()) {
            "ideClasspath() references ${onlyInClasspath.size} JARs not found on filesystem:\n" +
                    onlyInClasspath.joinToString("\n") { "  $it" }
        }

        // Apply structural exception rules (jbr, lib subdirs, plugin non-lib, plugin lib subdirs)
        val afterStructural = onlyInFilesystem.filter { !isStructuralException(it) }

        // Remaining should all be plugins/*/lib/modules/*.jar — check against explicit allowlist
        val expectedUnloaded = unloadedContentModules(ideProduct ?: "IU", ideBuild)
        val unexplained = afterStructural.filter { it !in expectedUnloaded }.sorted()

        // Detect stale allowlist entries (modules that were expected to be unloaded
        // but are now either loaded or removed from the IDE distribution)
        val staleEntries = (expectedUnloaded - onlyInFilesystem.toSet()).sorted()

        if (unexplained.isNotEmpty() || staleEntries.isNotEmpty()) {
            Assertions.fail<Nothing>(buildString {
                if (unexplained.isNotEmpty()) {
                    appendLine("${unexplained.size} JAR(s) on filesystem but not in classpath, not covered by any exception rule:")
                    appendLine("Add each to the appropriate category in UNLOADED_CONTENT_MODULES or fix isStructuralException().")
                    unexplained.forEach { appendLine("  $it") }
                }
                if (staleEntries.isNotEmpty()) {
                    appendLine("${staleEntries.size} JAR(s) in UNLOADED_CONTENT_MODULES allowlist but not found as unloaded (now loaded or removed?):")
                    staleEntries.forEach { appendLine("  $it") }
                }
            })
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `compile script importing AnnotatedElementsSearch from content module`() {
        session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.psi.search.searches.AnnotatedElementsSearch
                import com.intellij.psi.search.GlobalSearchScope
                import com.intellij.psi.JavaPsiFacade
                import com.intellij.psi.PsiMethod

                val count = readAction {
                    val scope = GlobalSearchScope.projectScope(project)
                    val facade = JavaPsiFacade.getInstance(project)
                    val cls = facade.findClass("java.lang.Deprecated", scope)
                        ?: return@readAction -1
                    AnnotatedElementsSearch.searchPsiMethods(cls, scope).findAll().size
                }
                if (count >= 0) {
                    println("CONTENT_MODULE_OK: found ${'$'}count @Deprecated methods")
                } else {
                    println("CONTENT_MODULE_OK: no java.lang.Deprecated on classpath")
                }
            """.trimIndent(),
            taskId = "content-module-classpath",
            reason = "Verify AnnotatedElementsSearch (intellij.java.indexing content module) compiles",
        ).assertExitCode(0)
    }

    private fun extractSection(output: String, sectionName: String): List<String> {
        val headerPattern = "=== ${sectionName}:"
        val lines = output.lines()
        val startIdx = lines.indexOfFirst { it.contains(headerPattern) }
        if (startIdx < 0) return emptyList()
        return lines.drop(startIdx + 1)
            .takeWhile { !it.contains("===") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun extractValue(output: String, key: String): String? {
        return output.lines()
            .firstOrNull { it.startsWith("$key:") }
            ?.removePrefix("$key:")
            ?.trim()
    }
}
