plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

dependencies {
    // The ONLY runtime dependency is kotlinx-serialization-json (already used across the build, e.g.
    // :website-gen / :installer-gen). HTML is emitted via plain Kotlin string building — no kotlinx.html —
    // so the rendered dashboard is a single self-contained file with zero extra deps. There are NO
    // project() dependencies on purpose: this module compiles on its own and never drags in the IntelliJ
    // plugin side, so the report task stays fast and runnable on a bare CI agent.
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

// Generate the experiments comparison dashboard (with-vs-without MCP Steroid, per task, per agent)
// from a directory of collected per-build inputs. Knows no server details and does no downloading — the
// input dir is laid out by a local test run (or by a separate data-collection step); this task only reads
// files and writes a self-contained index.html. The same core powers a CI report tab and a local run.
//
//   ./gradlew :experiments-report:generateExperimentsReport \
//       --args="--input <dir> --out <dir>/index.html [--title '…']"
val generateExperimentsReport = tasks.register<JavaExec>("generateExperimentsReport") {
    group = "report"
    description = "Render the test-experiments with/without-MCP comparison dashboard (index.html) from a collected input dir."
    mainClass.set("com.jonnyzzz.mcpSteroid.report.ReportMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Always re-run: the collected inputs change every run and never depend on the source tree.
    outputs.upToDateWhen { false }
}
