plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

dependencies {
    // :installer-gen is the lower build-tooling module — it owns JDK detection AND the shared HTTP/cache
    // infra (KtorHttpFetcher). This is the ONLY project() dependency: :installer-gen has no IntelliJ deps,
    // so `generateWebsite` still never builds the plugin side of the project.
    implementation(project(":installer-gen"))
    // kotlinx-serialization for the GitHub API JSON + version.json. ZIP/XML stay JDK built-in.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

val websiteGenMain = "com.jonnyzzz.mcpSteroid.websitegen.WebsiteArtifactsKt"

// Generate the website's release-derived static artifacts (version.json + updatePlugins.xml) straight
// into website/static. The task KNOWS ALL PATHS: VERSION + output dir + release-notes are derived from
// the project layout, so callers (the website Makefile / CI) invoke it with no arguments. It depends ONLY
// on this module's classes (no project() deps), so it never builds the rest of the project — fast.
val generateWebsite by tasks.registering(JavaExec::class) {
    group = "website"
    description = "Generate ALL website static files (version.json + updatePlugins.xml + install.sh + install.ps1 + EULA + LICENSE) into website/build/generated-static."
    mainClass.set(websiteGenMain)
    classpath = sourceSets["main"].runtimeClasspath
    // The website Make contract is a SINGLE task that produces every static file. This task writes
    // version.json + updatePlugins.xml (+ copies EULA/LICENSE below); the installer scripts come from
    // :installer-gen:generateInstaller, which writes into the same dir — so depend on it.
    dependsOn(":installer-gen:generateInstaller")

    val eula = rootProject.layout.projectDirectory.file("EULA")
    val license = rootProject.layout.projectDirectory.file("LICENSE")
    val versionFile = rootProject.layout.projectDirectory.file("VERSION")
    // Generated static files go into website/build/ (a `build/` dir → already gitignored, never the
    // tracked source tree), which Hugo merges via its `staticDir` list. Adding a new generated file needs
    // no .gitignore edit.
    val outDir = rootProject.layout.projectDirectory.dir("website/build/generated-static")
    val notesDir = rootProject.layout.projectDirectory.dir("release/notes")
    inputs.file(versionFile)
    // Always re-run: the published GitHub release (and its notes) can change without VERSION changing, so
    // we deliberately do not cache or skip on up-to-date.
    outputs.upToDateWhen { false }

    doFirst {
        val version = versionFile.asFile.readText().trim()
        require(version.isNotEmpty()) { "VERSION file (${versionFile.asFile}) is empty" }
        outDir.asFile.mkdirs()
        val resolved = mutableListOf("--version", version, "--out-dir", outDir.asFile.absolutePath)
        val notes = notesDir.file("$version.md").asFile
        if (notes.isFile) {
            resolved += listOf("--notes", notes.absolutePath)
        }
        args = resolved
    }
    // Own EULA + LICENSE too, so ALL served static files come from this one task (single source of truth),
    // not an out-of-band Makefile copy into a Gradle-owned dir. Copied from the repo root, served verbatim.
    doLast {
        outDir.asFile.mkdirs()
        eula.asFile.copyTo(outDir.file("EULA").asFile, overwrite = true)
        license.asFile.copyTo(outDir.file("LICENSE").asFile, overwrite = true)
    }
}

// `generateJdkModel` moved to :installer-gen (it owns JDK detection now).
