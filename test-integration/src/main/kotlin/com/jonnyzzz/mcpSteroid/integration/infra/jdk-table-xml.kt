/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.jdom2.Document
import org.jdom2.Element

/**
 * Generates `options/jdk.table.xml` from infra code (NOT inside IntelliJ) so the JDK
 * table can be pre-written into the IDE config dir BEFORE the IDE starts. This lets
 * Gradle auto-import resolve the project JDK at project-open time instead of stalling
 * for ~8 minutes on `Observation.awaitConfiguration` while `mcpRegisterJdks` races to
 * register JDKs after the IDE is already up (see waitForProjectReady → mcpTriggerImportAndWait).
 *
 * The rendering mirrors IntelliJ's own `JavaSdkImpl`/`ProjectJdkImpl` serialization:
 *   - classPath  = `jrt://<home>!/<module>` for each module in `release`'s MODULES, SORTED
 *                  (matches `JavaSdkImpl.findClasses` + `Collections.sort`);
 *                  for JDK 8 (non-modular): the jar roots under `jre/lib` and `jre/lib/ext`, SORTED.
 *   - sourcePath = top-level dirs of `src.zip` in zip-entry order (matches `addArchiveSourceUrls`'s
 *                  `LinkedHashSet`); a single `src.zip!/` root when there is no `java.base` dir (JDK 8).
 *   - annotationsPath = the constant jdkAnnotations.jar root.
 *   - version    = `Eclipse Temurin <JAVA_VERSION>` read from the JDK's `release` file
 *                  (so JDK 8 shows `1.8.0_492`, not `8`). No ` - aarch64` arch suffix.
 *
 * A JdkTableIntegrationTest validates this output equals what a live IntelliJ produces
 * via the API path (`mcpRegisterJdks`) on the same host.
 */

internal const val JDK_ANNOTATIONS_URL =
    "jar://\$APPLICATION_HOME_DIR\$/plugins/java/lib/resources/jdkAnnotations.jar!/"

/** A single `<jdk>` entry; [classUrls] already sorted, [sourceUrls] already in IntelliJ order. */
data class JdkTableEntry(
    val name: String,
    val versionString: String,
    val homePath: String,
    val classUrls: List<String>,
    val sourceUrls: List<String>,
)

/** A JDK discovered in the container, before alias expansion. */
data class DiscoveredJdk(
    /** Bare alias, e.g. "21" or "8". */
    val majorAlias: String,
    val versionString: String,
    val homePath: String,
    val classUrls: List<String>,
    val sourceUrls: List<String>,
) {
    /** Three aliases per JDK so VCS-pinned names (`corretto-21`, `temurin-21`) all resolve. */
    fun toEntries(): List<JdkTableEntry> =
        listOf(majorAlias, "corretto-$majorAlias", "temurin-$majorAlias").map { name ->
            JdkTableEntry(name, versionString, homePath, classUrls, sourceUrls)
        }
}

/** Render the full `jdk.table.xml` using jdom2 (IntelliJ's own XML library). */
fun renderJdkTableXml(entries: List<JdkTableEntry>): String {
    val component = Element("component").setAttribute("name", "ProjectJdkTable")
    for (e in entries) {
        val jdk = Element("jdk").setAttribute("version", "2")
        jdk.addContent(Element("name").setAttribute("value", e.name))
        jdk.addContent(Element("type").setAttribute("value", "JavaSDK"))
        jdk.addContent(Element("version").setAttribute("value", e.versionString))
        jdk.addContent(Element("homePath").setAttribute("value", e.homePath))

        val roots = Element("roots")
        roots.addContent(compositeRootList("annotationsPath", listOf(JDK_ANNOTATIONS_URL)))
        roots.addContent(compositeRootList("classPath", e.classUrls))
        // javadocPath must still carry an empty `<root type="composite" />` — IntelliJ's
        // ProjectJdkImpl.readExternal does `.get(0)` on each root type's children and throws
        // IndexOutOfBoundsException at project open if the element is empty.
        roots.addContent(compositeRootList("javadocPath", emptyList()))
        roots.addContent(compositeRootList("sourcePath", e.sourceUrls))
        jdk.addContent(roots)

        component.addContent(jdk)
    }
    val root = Element("application").addContent(component)
    return Document(root).toIdeaXml() + "\n"
}

private fun compositeRootList(tag: String, urls: List<String>): Element {
    val composite = Element("root").setAttribute("type", "composite")
    for (url in urls) {
        composite.addContent(Element("root").setAttribute("url", url).setAttribute("type", "simple"))
    }
    return Element(tag).addContent(composite)
}

/**
 * Discover every Temurin JDK under `/usr/lib/jvm/` in the container and build its
 * jdk.table entry data by reading the same artifacts IntelliJ reads.
 */
fun discoverContainerJdks(driver: ContainerDriver): List<DiscoveredJdk> {
    val homePaths = driver.startProcessInContainer {
        this.args("bash", "-c", "ls -1d /usr/lib/jvm/temurin-*-jdk-* 2>/dev/null || true")
            .timeoutSeconds(10).quietly()
            .description("list Temurin JDKs in /usr/lib/jvm")
    }.assertExitCode(0) { "Listing Temurin JDKs failed: $stderr" }
        .stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()

    return homePaths.map { home -> discoverJdk(driver, home) }
}

private fun discoverJdk(driver: ContainerDriver, home: String): DiscoveredJdk {
    val rawMajor = home.substringAfterLast("/temurin-").substringBefore("-jdk")
    require(rawMajor.isNotEmpty()) { "Failed to parse JDK major from $home" }
    // Legacy edge case: JDK <= 8 is named "1.8" / "1.7" / ... — not "8". Modern JDKs use the bare major.
    val majorAlias = if ((rawMajor.toIntOrNull() ?: Int.MAX_VALUE) <= 8) "1.$rawMajor" else rawMajor

    val release = readReleaseProperties(driver, home)
    val javaVersion = release["JAVA_VERSION"]
        ?: error("No JAVA_VERSION in $home/release")
    val versionString = "Eclipse Temurin $javaVersion"

    val modules = release["MODULES"]?.split(' ')?.map { it.trim() }?.filter { it.isNotEmpty() }

    val classUrls: List<String>
    if (!modules.isNullOrEmpty()) {
        // Modular runtime (JDK 9+): jrt module roots, sorted (JavaSdkImpl.findClasses).
        classUrls = modules.map { "jrt://$home!/$it" }.sorted()
    } else {
        // Legacy JDK 8: jar roots from jre/lib + jre/lib/ext, sorted.
        classUrls = driver.startProcessInContainer {
            this.args("bash", "-c", "ls -1 $home/jre/lib/*.jar $home/jre/lib/ext/*.jar 2>/dev/null || true")
                .timeoutSeconds(10).quietly().description("list JDK 8 classpath jars in $home")
        }.assertExitCode(0) { "Listing JDK8 jars failed: $stderr" }
            .stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { "jar://$it!/" }.sorted()
    }

    val srcZip = "$home/src.zip"
    val topDirs = listSrcZipTopDirs(driver, srcZip)
    val sourceUrls = when {
        topDirs.isEmpty() -> emptyList()
        topDirs.contains("java.base") -> topDirs.map { "jar://$srcZip!/$it" }
        else -> listOf("jar://$srcZip!/")
    }

    return DiscoveredJdk(majorAlias, versionString, home, classUrls, sourceUrls)
}

private fun readReleaseProperties(driver: ContainerDriver, home: String): Map<String, String> {
    val text = driver.startProcessInContainer {
        this.args("cat", "$home/release").timeoutSeconds(10).quietly()
            .description("read $home/release")
    }.assertExitCode(0) { "Reading $home/release failed: $stderr" }.stdout
    return text.lines().mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim().trim('"')
        key to value
    }.toMap()
}

/** Unique top-level directory names of src.zip, in zip-entry order (mirrors LinkedHashSet). */
private fun listSrcZipTopDirs(driver: ContainerDriver, srcZip: String): List<String> {
    val out = driver.startProcessInContainer {
        // unzip -Z1 lists entry names in archive order; keep the first path segment.
        this.args("bash", "-c", "unzip -Z1 '$srcZip' 2>/dev/null || true")
            .timeoutSeconds(30).quietly().description("list src.zip entries of $srcZip")
    }.assertExitCode(0) { "Listing src.zip failed: $stderr" }.stdout

    val seen = LinkedHashSet<String>()
    for (name in out.lineSequence()) {
        val n = name.trim()
        val slash = n.indexOf('/')
        if (slash > 0) seen.add(n.substring(0, slash))
    }
    return seen.toList()
}

/** Discover JDKs in the container and render the full jdk.table.xml. */
fun generateJdkTableXml(driver: ContainerDriver): String =
    renderJdkTableXml(discoverContainerJdks(driver).flatMap { it.toEntries() })
