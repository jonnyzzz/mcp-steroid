import com.jonnyzzz.mcpSteroid.gradle.GenerateMetadataTask
import com.jonnyzzz.mcpSteroid.gradle.configurePathingJarClasspath
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.SortedSet

plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

val ktorVersion = "3.3.2"

// Resolvable: pulls :ij-plugin's `buildPlugin` archive through the "plugin-zip"
// Usage attribute (same hook :test-integration uses). The distribution bundles
// this archive directly as ij-plugin.zip.
val ijPluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

// Resolvable: pulls :intellij-downloader's extracted 7-Zip binaries
// tree through the "seven-zip-binaries" Usage attribute. The directory
// (not a zip) lands here so distZip can copy it verbatim into 7z/.
val sevenZipBinaries by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "seven-zip-binaries"))
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.posthog:posthog-server:2.3.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation(project(":closeable-stack"))
    implementation(project(":ai-agents"))

    // MCP transport: framed/NDJSON parser + McpStdioServer (replaces the old
    // StdioServer in this module — kept compiled but no longer wired into main()).
    implementation(project(":mcp-stdio"))

    // McpSteroidTools — registers the steroid_* tool surface on an McpServerCore.
    // Brings :mcp-core and :prompts transitively.
    implementation(project(":mcp-steroid-server"))

    // IDE-free ExecutionStorage core. Lets devrig persist execution
    // history with the same on-disk layout the IntelliJ plugin uses, so
    // downstream tooling that reads .idea/mcp-steroid/{eid}/ artefacts
    // works against both backends without conditional logic.
    implementation(project(":execution-storage"))

    // Managed backends reuse the existing IntelliJ downloader/unpacker instead
    // of carrying a second product-feed / archive-extraction implementation.
    implementation(project(":intellij-downloader"))

    // Bundles :ij-plugin's buildPlugin archive into the distZip as ij-plugin.zip.
    // Resolved through the `ijPluginZip` configuration above.
    ijPluginZip(project(":ij-plugin"))

    // Bundles :intellij-downloader's extracted 7-Zip binaries into the distZip under 7z/.
    // Resolved through the `sevenZipBinaries` configuration above.
    sevenZipBinaries(project(":intellij-downloader"))

    // SLF4J binding for the launcher. We use Logback (not slf4j-simple) so
    // operators can drop in a `logback.xml` to add appenders, change levels,
    // or route specific loggers — slf4j-simple has no real configuration
    // surface. The bundled `logback.xml` (in src/main/resources) routes
    // everything to stderr only — never stdout, which is reserved for MCP
    // NDJSON frames.
    implementation("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Spin up an in-process Ktor server in monitor round-trip tests so the
    // monitor's HTTP client talks to a real socket. testImplementation only —
    // the production `devrig` binary stays a pure ktor-client.
    testImplementation("io.ktor:ktor-server-core:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio:$ktorVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "devrig"
    mainClass.set("com.jonnyzzz.mcpSteroid.devrig.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Use a pathing JAR for the launcher classpath so a deep content-addressed install path can't blow
// cmd.exe's 8191-char limit on Windows ("input line too long"). Shared logic; same jar on every OS.
configurePathingJarClasspath()

tasks.jar {
    archiveBaseName.set("devrig")
}

// Release artifact name is `devrig-<version>.zip` — e.g. `devrig-0.96-<gitHash>.zip`,
// matching the plugin zip's `mcp-steroid-<version>-<hash>.zip` convention. This is
// exactly Gradle's default `<archiveBaseName>-<version>` form, so only the base name
// is overridden.
tasks.distZip {
    archiveBaseName.set("devrig")
}

tasks.distTar {
    archiveBaseName.set("devrig")
}

tasks.startScripts {
    doFirst {
        outputDir?.deleteRecursively()
    }
    doLast {
        // Backport generic devrig knobs into devrig's OWN launchers (unix + windows), not the test harness:
        //  - DEVRIG_JAVA_HOME overrides JAVA_HOME — devrig is class-file v69 (jvmToolchain 25) and must
        //    launch on Java 25; this lets a caller point devrig at a Java 25 without touching the caller's
        //    JAVA_HOME (e.g. an environment whose default `java` is older).
        //  - DEVRIG_JVM_OPTS appends JVM options for the devrig launch (folded into the Gradle app opts
        //    var DEVRIG_OPTS, which both launchers already pass to the JVM).
        // Both are generic + non-breaking — applied only when set; the standard resolution is otherwise
        // untouched. Fail-fast if a Gradle template marker moves, so we never ship the default unpatched.

        // Unix launcher (bin/devrig): inject before the standard Java-command resolution.
        run {
            val marker = "# Determine the Java command to use to start the JVM."
            val text = unixScript.readText()
            require(text.contains(marker)) {
                "devrig unix start-script template changed: injection marker not found in ${unixScript.absolutePath}."
            }
            val inject = buildString {
                appendLine("# devrig: Java 25 (class-file v69). DEVRIG_JAVA_HOME overrides JAVA_HOME; DEVRIG_JVM_OPTS")
                appendLine("# appends JVM options. Both only when set; the caller's environment is left untouched.")
                appendLine("if [ -n \"\${DEVRIG_JAVA_HOME:-}\" ] ; then JAVA_HOME=\"\$DEVRIG_JAVA_HOME\" ; fi")
                appendLine("if [ -n \"\${DEVRIG_JVM_OPTS:-}\" ] ; then DEVRIG_OPTS=\"\${DEVRIG_OPTS:-} \$DEVRIG_JVM_OPTS\" ; fi")
                appendLine("# DEVRIG_DEBUG: attach a JDWP agent on a FREE port from the 100-port range 23900-23999,")
                appendLine("# seeded by this process's PID so multiple concurrent devrig processes don't clash on a port")
                appendLine("# (a clash would crash the JVM with \"Address already in use\"). quiet=y keeps the agent's")
                appendLine("# listening line OFF stdout (the MCP JSON-RPC channel); suspend=n so devrig never waits for a")
                appendLine("# debugger. The free-port probe needs bash (/dev/tcp); without bash we fall back to the")
                appendLine("# PID-seeded base port. The chosen port is announced on stderr only.")
                appendLine("if [ -n \"\${DEVRIG_DEBUG:-}\" ] ; then")
                appendLine("    DEVRIG_DEBUG_PORT=\"\"")
                appendLine("    if command -v bash >/dev/null 2>&1 ; then")
                appendLine("        DEVRIG_DEBUG_PORT=\$(DR_PID=\$\$ bash -c 'b=23900;r=100;o=\$((DR_PID%r));i=0;while [ \$i -lt \$r ];do p=\$((b+(o+i)%r));if ! (exec 3<>\"/dev/tcp/127.0.0.1/\$p\") 2>/dev/null;then echo \$p;exit 0;fi;exec 3>&- 2>/dev/null||true;i=\$((i+1));done;echo \$((b+o))')")
                appendLine("    fi")
                appendLine("    [ -n \"\$DEVRIG_DEBUG_PORT\" ] || DEVRIG_DEBUG_PORT=\$((23900 + \$\$ % 100))")
                appendLine("    DEVRIG_OPTS=\"\${DEVRIG_OPTS:-} -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,quiet=y,address=*:\$DEVRIG_DEBUG_PORT\"")
                appendLine("    echo \"devrig: JDWP debug listening on port \$DEVRIG_DEBUG_PORT (DEVRIG_DEBUG set)\" >&2")
                appendLine("fi")
                appendLine()
                append(marker)
            }
            unixScript.writeText(text.replaceFirst(marker, inject))
        }

        // Windows launcher (devrig.bat): inject before the standard JAVA_HOME check.
        run {
            val marker = "if defined JAVA_HOME goto findJavaFromJavaHome"
            val text = windowsScript.readText()
            require(text.contains(marker)) {
                "devrig windows start-script template changed: injection marker not found in ${windowsScript.absolutePath}."
            }
            val inject = buildString {
                appendLine("@rem devrig: DEVRIG_JAVA_HOME overrides JAVA_HOME; DEVRIG_JVM_OPTS appends JVM options. Only when set.")
                appendLine("if not \"%DEVRIG_JAVA_HOME%\"==\"\" set \"JAVA_HOME=%DEVRIG_JAVA_HOME%\"")
                appendLine("if not \"%DEVRIG_JVM_OPTS%\"==\"\" set \"DEVRIG_OPTS=%DEVRIG_OPTS% %DEVRIG_JVM_OPTS%\"")
                appendLine("@rem DEVRIG_DEBUG: attach a JDWP agent on a FREE port from the 100-port range 23900-23999,")
                appendLine("@rem PID-seeded (via PowerShell) so concurrent devrig processes don't clash on a port. quiet=y")
                appendLine("@rem keeps the agent's listening line off stdout (the MCP JSON-RPC channel); suspend=n so devrig")
                appendLine("@rem never waits for a debugger.")
                // Set DEVRIG_OPTS INSIDE the for/f loop using the loop var %%P. Doing it via an
                // intermediate %DEVRIG_DEBUG_PORT% + a nested `if` inside the parenthesised block fails:
                // batch expands %DEVRIG_DEBUG_PORT% at block-PARSE time (before the for/f sets it), so the
                // opt was never applied (validated on Windows). %%P is the loop value, expanded per-iteration.
                appendLine("if not \"%DEVRIG_DEBUG%\"==\"\" (")
                appendLine("    for /f \"usebackq tokens=*\" %%P in (`powershell -NoProfile -Command \"\$b=23900;\$r=100;\$o=[System.Diagnostics.Process]::GetCurrentProcess().Id %% \$r;for(\$i=0;\$i -lt \$r;\$i++){\$p=\$b+((\$o+\$i) %% \$r);try{\$l=New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any,\$p);\$l.Start();\$l.Stop();Write-Output \$p;break}catch{}}\"`) do set \"DEVRIG_OPTS=%DEVRIG_OPTS% -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,quiet=y,address=*:%%P\"")
                appendLine(")")
                append(marker)
            }
            windowsScript.writeText(text.replaceFirst(marker, inject))
        }
    }
}

// Provider<File> for the resolved plugin zip — derived from `Configuration.elements`
// so Gradle carries the task dependency on `:ij-plugin:buildPlugin` through into
// downstream tasks automatically. Going through `ijPluginZip.singleFile` is eager
// and strips the Buildable, leaving the consumer without a path to producing the zip.
val ijPluginZipFile = ijPluginZip.elements.map { it.single().asFile }
val sevenZipBinariesDir = sevenZipBinaries.elements.map { it.single().asFile }
val sevenZipLicenseFile = sevenZipBinariesDir.map { it.resolve("7z/License.txt") }

distributions {
    main {
        contents {
            // Repo-root EULA — same file `:ij-plugin` already ships inside its
            // plugin zip. Bundled at the devrig dist root so the binary carries
            // its own license text alongside the launcher and bundled plugin.
            from(rootProject.layout.projectDirectory.file("EULA"))

            // Keep the plugin as the original archive. Managed backend install
            // expands it on demand, which avoids re-packing the plugin payload and
            // preserves the mode bits recorded by :ij-plugin.
            from(ijPluginZipFile) {
                rename { "ij-plugin.zip" }
            }

            // 7-Zip Windows binaries from :intellij-downloader's extractSevenZipResources.
            // Placed under a dedicated `7z/` folder (NOT `bin/`) to keep them off the
            // JVM's `java.library.path` search list — `bin/` is often on that list and
            // a stray `System.loadLibrary("7z")` would pick the wrong DLL by mistake.
            // The companion License.txt sits next to the binaries mirroring 7-Zip's own
            // distribution layout. devrig calls <install>/7z/7z.exe directly — no
            // extraction-on-first-use, no classpath unpacking. The +x bit is preserved.
            into("7z") {
                from(sevenZipBinariesDir.map { it.resolve("7z/win-x64/7z.exe") })
                from(sevenZipBinariesDir.map { it.resolve("7z/win-x64/7z.dll") })
                from(sevenZipBinariesDir.map { it.resolve("7z/win-x64/License.txt") })
                eachFile {
                    if (file.canExecute()) {
                        permissions { unix("rwxr-xr-x") }
                    }
                }
            }

            // Operator-facing license consolidation. The component-local copies
            // remain in place; these entries give admins one index under licenses/.
            into("licenses/seven-zip") {
                from(sevenZipLicenseFile)
            }
            into("licenses/mcp-steroid") {
                from(rootProject.layout.projectDirectory.file("EULA"))
            }

            // JDK bundling intentionally disabled — devrig expects Java on PATH (see TODO-NPX-BOOTSTRAPPER.md).
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // Hand the build's project.version through to DevrigVersionMetadataTest so it can
    // end-to-end-assert the generated runtime value.
    systemProperty("devrig.expected.version", version.toString())
    // Unit tests must never reach PostHog over the network (DevrigBeacon).
    systemProperty("devrig.beacon.disabled", "true")
}

kotlin {
    jvmToolchain(25)
}

// `project.version` rendered as a String once so both the metadata generator
// (just below) and the bundled-libraries verifier (further down) can use it without
// re-evaluating Gradle's Provider chain.
val devrigVersion: String = version.toString()

// Generate the same encoded Kotlin metadata shape as :ij-plugin's PluginMetadata,
// but in devrig's package so runtime version reporting needs no classpath
// resource fallback.
val generatedSourcesPath = layout.buildDirectory.dir("generated/kotlin")
val generateDevrigVersionMetadata by tasks.registering(GenerateMetadataTask::class) {
    group = "build"
    description = "Generate encoded devrig version metadata"

    versionString.set(devrigVersion)
    packageName.set("com.jonnyzzz.mcpSteroid.devrig")
    fileName.set("DevrigVersionMetadata")
    objectName.set("DevrigVersionMetadata")
    functionName.set("getDevrigVersion")
    functionKdoc.set("devrig version (encoded)")
    outputFile.set(generatedSourcesPath.map { it.file("DevrigVersionMetadata.kt") })
}

kotlin.sourceSets.main {
    kotlin.srcDir(generatedSourcesPath)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateDevrigVersionMetadata)
}

// Dedicated source set for stdio MCP server integration tests
// (CliMcpStdioIntegrationTest). The tests spawn `bin/devrig` from the
// `installDist` output as a subprocess and exchange JSON-RPC frames over stdio.
// They are NOT part of the default `:npx-kt:test` run — invoke explicitly via
// `./gradlew :npx-kt:integrationTest`.
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output +
            sourceSets["test"].compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets["test"].runtimeClasspath
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "integrationTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(kotlin("test-junit5"))

    // Docker harness: ContainerDriver + buildDockerImage + AISessionBase, plus
    // DockerClaudeSession / DockerCodexSession / DockerGeminiSession used by
    // the Cli{Claude,Codex,Gemini}IntegrationTest classes.
    "integrationTestImplementation"(project(":test-helper"))
    // StdioMcpCommand + the agent MCP-add arg builders.
    "integrationTestImplementation"(project(":ai-agents"))
}

val installDistTask = tasks.named<Sync>("installDist") {
    doFirst {
        destinationDir.takeIf { it.exists() }?.walkBottomUp()?.forEach { file ->
            if (!file.setWritable(true)) {
                logger.debug("installDist pre-clean: could not chmod +w on {}", file)
            }
        }
    }
}

tasks.register<Test>("integrationTest") {
    description = "Stdio MCP server integration tests (subprocess-driven). Requires installDist."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    // devrig is jvmToolchain(25); every host-launched devrig subprocess in these tests
    // (CliOptions, CliMcpStdioStdoutCleanliness, fake-IDE bridge, …) must run under a JDK
    // 25. TeamCity runs the Gradle step under JDK 21 and exports JAVA_HOME=21 into the
    // build environment, which the devrig start-script would otherwise inherit and fail
    // with UnsupportedClassVersionError (class-file v69). Override JAVA_HOME for the test
    // JVM (and thus every subprocess it spawns) with the JDK the build itself runs on —
    // the daemon is pinned to 25 via gradle/gradle-daemon-jvm.properties.
    environment("JAVA_HOME", System.getProperty("java.home"))
    dependsOn(installDistTask)
    // Path to the launcher script produced by installDist. The application plugin
    // names the install dir after `application.applicationName`, not the project,
    // so resolve it via the task itself rather than hard-coding the directory.
    val launcherProvider = installDistTask.map { sync ->
        sync.destinationDir.resolve("bin/${application.applicationName}")
    }
    inputs.file(launcherProvider)
    doFirst {
        systemProperty("devrig.launcher", launcherProvider.get().absolutePath)
    }
}

val devrigPackageElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "devrig-package"))
    }
}

artifacts {
    add(devrigPackageElements.name, tasks.distZip)
}

tasks.named("assemble") {
    dependsOn(tasks.distZip)
}

// Modelled on :ij-plugin's `verifyBundledLibraries`. Locks down what `distZip` ships so
// transitive-dependency churn (a coroutine update, a Ktor bump, a new internal module)
// fails the build instead of silently changing what end users `npx`-install. Update
// `expectedFiles` below when the change is intentional.
val verifyBundledLibraries by tasks.registering {
    group = "verification"
    description = "List and verify libraries bundled in the devrig distZip"
    dependsOn(tasks.distZip)
    doLast {
        val zip = tasks.distZip.get().outputs.files.singleFile

        var allFiles: SortedSet<String> = run {
            val collected = mutableListOf<String>()
            zipTree(zip).visit {
                if (!isDirectory) {
                    val path = relativePath.pathString
                    collected += if (permissions.user.execute) "$path:X" else path
                }
            }
            collected
        }.toSortedSet()

        // distZip puts everything under a top-level `<archiveBaseName>-<version>/` dir
        // (the `application` plugin convention). Strip that prefix so the expected list
        // doesn't have to repeat it on every line.
        val distName = tasks.distZip.get().archiveFile.get().asFile.nameWithoutExtension
        val pluginPrefix = "$distName/"
        check(allFiles.all { it.startsWith(pluginPrefix) }) {
            "Entries must live under '$pluginPrefix': " + allFiles.map { it.substringBefore('/') }.toSortedSet()
        }
        allFiles = allFiles.map { it.removePrefix(pluginPrefix) }.toSortedSet()
        check(allFiles.isNotEmpty()) { "distZip has no entries" }

        val ijPluginZipEntry = "ij-plugin.zip"
        check(ijPluginZipEntry in allFiles) {
            "Expected $ijPluginZipEntry to be bundled in $distName"
        }
        check("$ijPluginZipEntry:X" !in allFiles) {
            "$ijPluginZipEntry must not be marked executable"
        }
        // licenses/ subtree: operator-facing consolidation of each bundled
        // component's license files. The plugin zip still carries its own copy
        // inside ij-plugin.zip; the 7-Zip license lives only here (the bin/ copy
        // would just be a duplicate of the operator-visible licenses/seven-zip one).
        val licensesPrefix = "licenses/"
        val licensesFiles = allFiles.filter { it.startsWith(licensesPrefix) }.toSortedSet()
        check(licensesFiles.isNotEmpty()) {
            "licenses/ subtree must be populated in $distName"
        }

        val expectedLicensesFiles = sortedSetOf(
            "licenses/README.md",
            "licenses/seven-zip/License.txt",
            "licenses/mcp-steroid/EULA",
        )
        if (licensesFiles != expectedLicensesFiles) {
            val missing = expectedLicensesFiles - licensesFiles
            val unexpected = licensesFiles - expectedLicensesFiles
            throw GradleException(buildString {
                appendLine("licenses/ subtree mismatch in :npx-kt devrig distZip!")
                if (missing.isNotEmpty()) {
                    appendLine("Missing entries:")
                    missing.forEach { appendLine("  - $it") }
                }
                if (unexpected.isNotEmpty()) {
                    appendLine("Unexpected entries:")
                    unexpected.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Update `expectedLicensesFiles` in npx-kt/build.gradle.kts if this change is intentional.")
            })
        }
        expectedLicensesFiles.forEach { sentinel ->
            check(sentinel in licensesFiles) {
                "licenses/ subtree missing sentinel '$sentinel'"
            }
        }
        allFiles = (allFiles - licensesFiles).toSortedSet()

        // Bundled Windows 7-Zip binaries live under a dedicated `7z/` folder
        // (NOT bin/ — see distZip comment about java.library.path). The +x bit
        // on 7z.exe / 7z.dll is not pinned: POSIX build hosts may strip it for
        // non-launcher files and Windows ignores it. Pre-strip `:X` from these
        // two entries so the final expectedFiles set comparison tolerates either
        // form. The launcher bit (bin/devrig, bin/devrig.bat) IS pinned.
        val sevenZipBinaryEntries = sortedSetOf("7z/7z.exe", "7z/7z.dll")
        sevenZipBinaryEntries.forEach { sentinel ->
            check(allFiles.any { it.removeSuffix(":X") == sentinel }) {
                "7z/ subtree missing sentinel '$sentinel'"
            }
        }
        allFiles = allFiles.map { entry ->
            if (entry.removeSuffix(":X") in sevenZipBinaryEntries) entry.removeSuffix(":X") else entry
        }.toSortedSet()

        val expectedFiles = sortedSetOf(
            // EULA — repo-root EULA at the distribution root, mirroring the
            // copy `:ij-plugin` ships inside its plugin zip.
            "EULA",
            "ij-plugin.zip",

            // Launchers — the `application` plugin marks BOTH executable in the zip
            // (Windows ignores the bit; Unix needs it for the shell launcher).
            "bin/devrig:X",
            "bin/devrig.bat:X",

            // Bundled 7-Zip Windows binaries + companion License.txt
            // (:intellij-downloader → 7z/). Executable bit pre-stripped above
            // before this set comparison for the .exe / .dll entries.
            "7z/7z.exe",
            "7z/7z.dll",
            "7z/License.txt",

            // Internal jars (this project + sibling subprojects).
            "lib/devrig-$devrigVersion.jar",
            // Pathing JAR (manifest Class-Path) the launcher's classpath points at. Spelled out
            // literally — NOT via pathingJarFileName() — so this assert independently pins the name
            // the production helper must produce (a bug in the helper can't corrupt both sides alike).
            "lib/devrig-$devrigVersion-classpath.jar",
            "lib/ai-agents-$devrigVersion.jar",
            "lib/closeable-stack-$devrigVersion.jar",
            "lib/execution-storage-$devrigVersion.jar",
            "lib/intellij-downloader-$devrigVersion.jar",
            "lib/mcp-core-$devrigVersion.jar",
            "lib/mcp-steroid-server-$devrigVersion.jar",
            "lib/mcp-stdio-$devrigVersion.jar",
            "lib/prompts-$devrigVersion.jar",
            "lib/prompts-api-$devrigVersion.jar",

            // Kotlin runtime.
            "lib/kotlin-stdlib-2.3.20.jar",
            "lib/kotlin-stdlib-jdk7-2.3.20.jar",
            "lib/kotlin-stdlib-jdk8-2.3.20.jar",
            "lib/kotlinx-coroutines-core-jvm-1.10.2.jar",
            "lib/kotlinx-coroutines-slf4j-1.10.2.jar",
            "lib/kotlinx-io-bytestring-jvm-0.8.0.jar",
            "lib/kotlinx-io-core-jvm-0.8.0.jar",
            "lib/kotlinx-serialization-core-jvm-1.9.0.jar",
            "lib/kotlinx-serialization-json-jvm-1.9.0.jar",

            // CLI parsing (Clikt) and its terminal/help rendering transitives.
            "lib/clikt-jvm.jar",
            "lib/colormath-jvm.jar",
            "lib/fastutil-core-8.5.12.jar",
            "lib/jna-5.14.0.jar",
            "lib/markdown-jvm-0.7.0.jar",
            "lib/mordant-jvm.jar",

            // Ktor client transitives (CIO engine).
            "lib/ktor-client-cio-jvm-3.3.2.jar",
            "lib/ktor-client-core-jvm-3.3.2.jar",
            "lib/ktor-events-jvm-3.3.2.jar",
            "lib/ktor-http-cio-jvm-3.3.2.jar",
            "lib/ktor-http-jvm-3.3.2.jar",
            "lib/ktor-io-jvm-3.3.2.jar",
            "lib/ktor-network-jvm-3.3.2.jar",
            "lib/ktor-network-tls-jvm-3.3.2.jar",
            "lib/ktor-serialization-jvm-3.3.2.jar",
            "lib/ktor-sse-jvm-3.3.2.jar",
            "lib/ktor-utils-jvm-3.3.2.jar",
            "lib/ktor-websocket-serialization-jvm-3.3.2.jar",
            "lib/ktor-websockets-jvm-3.3.2.jar",

            // Analytics + HTTP transitives.
            "lib/posthog-6.4.0.jar",
            "lib/posthog-server-2.3.0.jar",
            "lib/okhttp-4.11.0.jar",
            "lib/okio-jvm-3.2.0.jar",
            "lib/gson-2.10.1.jar",

            // SLF4J + Logback (production logging binding).
            "lib/logback-classic-1.5.32.jar",
            "lib/logback-core-1.5.32.jar",
            "lib/slf4j-api-2.0.17.jar",

            // Other transitives.
            "lib/annotations-23.0.0.jar",
            "lib/commons-codec-1.19.0.jar",
            "lib/commons-compress-1.28.0.jar",
            "lib/commons-io-2.20.0.jar",
            "lib/commons-lang3-3.18.0.jar",
            "lib/xz-1.10.jar",
        ).toSortedSet()

        if (allFiles != expectedFiles) {
            val missing = expectedFiles - allFiles
            val unexpected = allFiles - expectedFiles
            throw GradleException(buildString {
                appendLine("Bundled libraries mismatch in :npx-kt devrig distZip!")
                if (missing.isNotEmpty()) {
                    appendLine("Missing entries:")
                    missing.forEach { appendLine("  - $it") }
                }
                if (unexpected.isNotEmpty()) {
                    appendLine("Unexpected entries:")
                    unexpected.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Actual entries:")
                allFiles.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Update `expectedFiles` in npx-kt/build.gradle.kts if this change is intentional.")
            })
        }
    }
}

tasks.test {
    dependsOn(verifyBundledLibraries)
}

tasks.distZip {
    finalizedBy(verifyBundledLibraries)
}

tasks.named("check") {
    dependsOn(verifyBundledLibraries)
}
