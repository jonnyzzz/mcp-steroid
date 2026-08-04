@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    // Numbered Kotlin 2.4.20 RC builds are published here before the final RC reaches Maven Central.
    // TODO(TODO-BTA-PERF.md): remove this repository once a regular Kotlin release contains KT-87743.
    maven("https://packages.jetbrains.team/maven/p/kt/dev") {
        content {
            includeGroup("org.jetbrains.kotlin")
        }
    }
    mavenCentral()
}

// Declarable bucket for the BTA implementation jars (dependencyScope — a
// resolvable configuration cannot have dependencies declared against it).
val btaImplDecl = configurations.dependencyScope("kotlinBuildToolsImpl")
val btaImplClasspath = configurations.resolvable("kotlinBuildToolsImplClasspath") {
    extendsFrom(btaImplDecl.get())

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

dependencies {
    // Keep the BTA API and implementation on the same version (-impl IS the snippet
    // compiler). This is intentionally independent from the Kotlin Gradle plugin version.
    // kotlin-build-tools-compat
    // is deliberately NOT bundled — it only adapts pre-2.3.0 impls to the
    // KotlinToolchains API; for impl >= 2.3.0 the ServiceLoader finds the
    // implementation directly and compat is dead weight.
    val kotlinxCoroutines = providers.gradleProperty("mcp.kotlinx.coroutines.version").get()

    api("org.jetbrains.kotlin:kotlin-build-tools-api:2.4.20-RC-197")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutines")

    btaImplDecl.name("org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.20-RC-197") {
        // The daemon execution flow is gone — in-process is the only path (the daemon
        // clears the compiler cache after each compilation, upstream KT-88183; re-check
        // when updating the kotlinc/BTA logic). These two jars exist solely for the
        // daemon *client* connection and are dead weight:
        //  - kotlin-compiler-runner: KotlinCompilerRunnerUtils.newDaemonConnection + CompilerOutputParser
        //    (its one in-process-path symbol, toArgumentStrings, ships inside kotlin-compiler-embeddable)
        //  - kotlin-daemon-client: the RMI client (BasicCompilerServicesWithResultsFacadeServer et al.),
        //    reached only via kotlin-compiler-runner
        // kotlin-daemon-embeddable MUST stay: BTA 2.4.20 RC links daemon-common eagerly even in-process
        // (JvmCompilationOperationImpl's constructor initializes
        // targetPlatform = CompileService.TargetPlatform.JVM), and kotlin-compiler-embeddable
        // declares kotlin-daemon-embeddable as a runtime dependency. Verified against the
        // v2.4.20 RC sources/bytecode and a standalone in-process compile probe.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-runner")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-daemon-client")
    }

    testImplementation("junit:junit:4.13.2")
}

// --- kotlinc distribution: the BTA implementation jars as real files ---
//
// The jars are laid out as a directory and shipped as-is (plugin dist `kotlinc/`
// folder — the successor of the old kotlinc dist; test JVMs get the directory
// via a system property). BTA loads them with a URLClassLoader and
// the compiler's FastJarFileSystem reads these very paths, so the jars MUST exist
// as plain files on disk — and nothing ever needs to be unpacked at runtime.
val btaImplJarsDir = layout.buildDirectory.dir("bta-impl-jars")

val syncBtaImplJars = tasks.register<Sync>("syncBtaImplJars") {
    description = "Stage Kotlin Build Tools implementation jars for the plugin distribution"
    from(btaImplClasspath)
    into(btaImplJarsDir)
}

// Consumable configuration — exposes the BTA impl jar directory as an artifact
val kotlincDistElements = configurations.create("kotlincDistElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "kotlinc-dist"))
    }
}

artifacts {
    add(kotlincDistElements.name, btaImplJarsDir) {
        builtBy(syncBtaImplJars)
    }
}

tasks.test {
    useJUnit()
    dependsOn(syncBtaImplJars)
    systemProperty(
        "mcp.steroid.bta.impl.dir",
        btaImplJarsDir.get().asFile.absolutePath,
    )
}
