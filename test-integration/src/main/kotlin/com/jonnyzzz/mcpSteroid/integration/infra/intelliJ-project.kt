/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File


/**
 * One build system a project uses, with its own root file. A project may have several
 * (e.g. a Gradle module alongside a Maven module), each rooted at a different file/dir.
 *
 * @param type     the external build system
 * @param rootFile relative path (from the project root) of this build system's root file,
 *                 e.g. "settings.gradle.kts", "build.gradle.kts", "pom.xml". Its parent
 *                 directory is the external project path used for import / gradleJvm config.
 */
data class ProjectBuildSystem(
    val type: BuildSystem,
    val rootFile: String,
)

sealed class IntelliJProject{
    abstract fun IntelliJProjectDriver.deploy()

    /**
     * Returns the HTTPS clone URL for the repository that this project deploys,
     * or null if this project is not backed by a remote git repository.
     *
     * Used by [IntelliJContainer.create] to warm the bare repo cache on the host
     * before the container starts, so [GitDriver.cloneFromCachedBare] can use the
     * fast local clone path instead of hitting the remote.
     */
    open fun getRepoUrlForCache(): String? = null

    /**
     * Warm host-side cache artifacts before container startup.
     *
     * Default behavior:
     * - if [getRepoUrlForCache] is non-null: warm bare git cache
     * - otherwise: no-op
     */
    open fun warmRepoCache(cacheDir: File) {
        println("[IDE-AGENT] Warming project cache artifacts in ${cacheDir.absolutePath} ...")
        val repoUrl = getRepoUrlForCache() ?: return
        BareRepoCache.ensureRepo(repoUrl, cacheDir)
    }

    /**
     * Relative path (from project root) of the file to open when the IDE starts.
     * When null, the default README.md / first source file fallback is used.
     */
    open val openFileOnStart: String? = null

    /**
     * JDK version this project targets (a name registered in the pre-written jdk.table.xml,
     * e.g. "21"). Used to set the project SDK and the Gradle JVM before/at project open so the
     * build-system import can resolve its JDK instead of stalling. Null for non-JVM IDEs.
     */
    open val jdkVersion: String? = "21"

    /**
     * Build systems this project actually uses, each with its own root file. A project may
     * declare more than one. [waitForProjectReady] imports each declared system with the proper
     * external-system API; an empty set means there is nothing to import (and no configuration to
     * await). Declaring the build system + root is what lets us pre-set its JVM and avoid the
     * ~8-min `Observation.awaitConfiguration` stall on an unconfigured Gradle project.
     */
    open val buildSystems: Set<ProjectBuildSystem> = emptySet()

    object TestProject : ProjectFromRepository(
        "test-project",
        openFile = "src/test/kotlin/com/jonnyzzz/mcpSteroid/demo/DemoByJonnyzzzTest.kt",
        jdkVersion = "21",
        buildSystems = setOf(ProjectBuildSystem(BuildSystem.GRADLE, "settings.gradle.kts")),
    )

    /**
     * Minimal IDEA project — just a README, no build system, no JDK. For tests where the project
     * content is irrelevant (dialog killer, infrastructure smoke tests): startup is fast because
     * `waitForProjectReady` skips JDK setup and build-system import.
     */
    object EmptyProject : ProjectFromRepository(
        "test-project-empty",
        openFile = "README.md",
        jdkVersion = null,
        buildSystems = emptySet(),
    )
    object PyCharmTestProject : ProjectFromRepository(
        "test-project-pycharm", openFile = "main.py", jdkVersion = null, buildSystems = emptySet(),
    )
    object GoLandTestProject : ProjectFromRepository(
        "test-project-goland", openFile = "main.go", jdkVersion = null, buildSystems = emptySet(),
    )
    object WebStormTestProject : ProjectFromRepository(
        "test-project-webstorm", openFile = "index.js", jdkVersion = null, buildSystems = emptySet(),
    )
    object RiderTestProject : ProjectFromRepository(
        "test-project-rider",
        openFile = "DemoRider.Tests/LeaderboardTests.cs",
        jdkVersion = null,
        buildSystems = emptySet(),
    )
    object CLionTestProject : ProjectFromRepository(
        "test-project-clion",
        openFile = "main.cpp",
        jdkVersion = null,
        buildSystems = emptySet(),
    )

    object MavenTestProject : ProjectFromRepository(
        "test-project-maven",
        openFile = "src/test/java/com/example/demo/CalculatorTest.java",
        jdkVersion = "21",
        buildSystems = setOf(ProjectBuildSystem(BuildSystem.MAVEN, "pom.xml")),
    )

    object ThisLoggerProject : ProjectFromRepository(
        "thislogger-project",
        openFile = "src/main/kotlin/com/example/util/Logging.kt",
        jdkVersion = "21",
        buildSystems = setOf(ProjectBuildSystem(BuildSystem.GRADLE, "settings.gradle.kts")),
    )

    object BroadleafCommerceProject : ProjectFromRemoteGit("https://github.com/BroadleafCommerce/BroadleafCommerce.git")
    object KeycloakProject : ProjectFromRemoteGit("https://github.com/keycloak/keycloak.git")
    object KillBillProject : ProjectFromRemoteGit("https://github.com/killbill/killbill.git")
    object ThingsBoardProject : ProjectFromRemoteGit("https://github.com/thingsboard/thingsboard.git")
    object YouTrackDbProject : ProjectFromRemoteGit("https://github.com/JetBrains/youtrackdb.git")
    object IntelliJPlatformGradlePluginProject : ProjectFromRemoteGit("https://github.com/JetBrains/intellij-platform-gradle-plugin.git")

    /** A real Android (Gradle) project, used to exercise Android Studio. Google's official base template. */
    object AndroidSampleProject : ProjectFromRemoteGit("https://github.com/android/architecture-templates.git")

    open class ProjectFromRepository protected constructor(
        val projectName: String,
        private val openFile: String?,
        override val jdkVersion: String?,
        override val buildSystems: Set<ProjectBuildSystem>,
    ) : IntelliJProject() {
        override val openFileOnStart: String? get() = openFile

        init {
            require(buildSystems.isEmpty() || jdkVersion != null) {
                "Project '$projectName' declares build systems $buildSystems but no jdkVersion — " +
                    "a JVM build system needs a JDK to import."
            }
            require(buildSystems.all { it.rootFile.isNotBlank() }) {
                "Project '$projectName' has a build system with a blank rootFile."
            }
        }
        override fun IntelliJProjectDriver.deploy() {
            console.writeInfo("Copying project $projectName files into container-local project-home...")
            val guestProjectDir = ijDriver.getGuestProjectDir()
            val hostProjectSourceDir = IdeTestFolders.dockerDir.resolve(projectName)
            require(hostProjectSourceDir.isDirectory) {
                "Project source directory does not exist: ${hostProjectSourceDir.absolutePath}"
            }

            container.startProcessInContainer {
                this
                    .args("rm", "-rf", guestProjectDir)
                    .timeoutSeconds(30)
                    .description("Remove stale project directory $guestProjectDir")
                    .quietly()
            }.awaitForProcessFinish().assertExitCode(0, "Failed to clean project directory $guestProjectDir")

            container.copyToContainer(hostProjectSourceDir, guestProjectDir)

            // docker cp on macOS Docker Desktop creates directories owned by root inside the container.
            // Fix ownership so the agent user can write to the project directory (e.g. create .idea/).
            // Must run as root (user 0:0) since the files are root-owned and only root can chown them.
            container.startProcessInContainer {
                this
                    .user("0:0")
                    .args("chown", "-R", "agent:agent", guestProjectDir)
                    .timeoutSeconds(30)
                    .description("Fix project directory ownership for agent user")
                    .quietly()
            }.awaitForProcessFinish().assertExitCode(0, "Failed to chown project directory $guestProjectDir")
        }
    }

    open class ProjectFromRemoteGit protected constructor(val repoUrl: String) : IntelliJProject() {
        override fun getRepoUrlForCache(): String = repoUrl

        override fun IntelliJProjectDriver.deploy() {
            val git = GitDriver(container)
            val guestProjectDir = ijDriver.getGuestProjectDir()

            // Derive owner/repo from URL (e.g. "keycloak/keycloak") for the cache path.
            val ownerAndRepo = repoUrl
                .removePrefix("https://github.com/")
                .trimEnd('/')
                .removeSuffix(".git")

            // Use the bare repo cache when it is mounted at /repo-cache inside the container.
            val clonedFromCache = git.cloneFromCachedBare(ownerAndRepo, guestProjectDir)
            if (!clonedFromCache) {
                console.writeInfo("Cache miss for $ownerAndRepo — cloning from $repoUrl ...")
                git.clone(repoUrl, guestProjectDir)
            }
        }
    }

    /**
     * Deploy a project by cloning a git repository at a specific commit and optionally
     * applying a patch. The project is deployed at the IDE's project-home path so
     * IntelliJ opens it directly on startup — no [steroid_open_project] call needed.
     *
     * Used by arena test runners (e.g. DpaiaArenaTest) to pre-deploy the test scenario
     * before IntelliJ starts, so that [waitForProjectReady] handles indexing as usual.
     *
     * @param cloneUrl         Full HTTPS clone URL (e.g. "https://github.com/dpaia/empty-maven-springboot3")
     * @param repoOwnerAndName Owner/repo without .git suffix (e.g. "dpaia/empty-maven-springboot3")
     * @param baseCommit       Git commit SHA to check out
     * @param testPatch        Unified diff to apply after checkout; empty string means no patch
     * @param displayName      Human-readable name used in console messages
     */
    class ProjectFromGitCommitAndPatch(
        val cloneUrl: String,
        val repoOwnerAndName: String,
        val baseCommit: String,
        val testPatch: String,
        val displayName: String,
        /**
         * Build system hint: "maven" or "gradle". When set to "maven", a minimal
         * `.idea/misc.xml` is pre-created so IntelliJ auto-imports as Maven instead
         * of showing the "Open or Import Project" dialog (which appears when the repo
         * contains both pom.xml and build.gradle).
         */
        val buildSystem: String = "",
    ) : IntelliJProject() {
        override fun getRepoUrlForCache(): String = cloneUrl

        override fun IntelliJProjectDriver.deploy() {
            console.writeInfo("Cloning $displayName ...")
            val git = GitDriver(container)
            val guestProjectDir = ijDriver.getGuestProjectDir()

            val clonedFromCache = git.cloneFromCachedBare(repoOwnerAndName, guestProjectDir)
            if (!clonedFromCache) {
                console.writeInfo("Cache miss — cloning from $cloneUrl ...")
                git.clone(cloneUrl, guestProjectDir, shallow = false, timeoutSeconds = 120)
            }

            git.checkout(guestProjectDir, baseCommit)

            if (testPatch.isNotBlank()) {
                console.writeInfo("Applying test patch for $displayName ...")
                git.applyPatch(guestProjectDir, testPatch)
            }

            if (buildSystem.equals("maven", ignoreCase = true)) {
                console.writeInfo("Pre-creating .idea/ Maven config for $displayName ...")
                val ideaDir = "$guestProjectDir/.idea"
                val miscXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project version="4">
                      <component name="MavenProjectsManager">
                        <option name="originalFiles">
                          <list>
                            <option value="${'$'}PROJECT_DIR${'$'}/pom.xml" />
                          </list>
                        </option>
                      </component>
                    </project>
                """.trimIndent()
                // modules.xml signals to IntelliJ that this is an existing IntelliJ project,
                // preventing the blocking "Open or Import Project" dialog that appears when
                // a directory has both pom.xml and build.gradle (ambiguous build system).
                // Without modules.xml IntelliJ shows the wizard before any project frame is
                // created; misc.xml alone is not sufficient in IntelliJ 2025.3.
                val modulesXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project version="4">
                      <component name="ProjectModuleManager">
                        <modules />
                      </component>
                    </project>
                """.trimIndent()
                container.writeFileInContainer("$ideaDir/misc.xml", miscXml)
                container.writeFileInContainer("$ideaDir/modules.xml", modulesXml)
            }

            console.writeSuccess("$displayName ready")
        }
    }
}

class IntelliJProjectDriver(
    val lifetime: CloseableStack,
    val container: ContainerDriver,
    val ijDriver: IntelliJDriver,
    val console: ConsoleDriver,
) {
    fun deployProject(project: IntelliJProject) {
        project.apply { deploy() }
    }
}
