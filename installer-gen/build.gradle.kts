plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Render `install.sh` + `install.ps1` from the committed coordinate files. A pure data-merge:
 * no devrig/plugin build dependency. Invoked by the website Makefile (shelling out to ./gradlew)
 * and by :test-integration (which consumes the output dir via a system property). Relative -P
 * paths resolve against the repo root so `-PoutDir=website/static` works from `cd .. && ./gradlew`.
 */
val generateInstaller by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate install.sh + install.ps1 from website/installer/*-coordinates.json."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath

    val outDirProp = project.findProperty("outDir") as String?
    val outDir = if (outDirProp != null) rootProject.file(outDirProp).absolutePath
                 else layout.buildDirectory.dir("installer").get().asFile.absolutePath
    val jdkCoords = rootProject.file((project.findProperty("jdkCoordinatesFile") as String?)
        ?: "website/installer/jdk-coordinates.json").absolutePath
    val devrigCoords = rootProject.file((project.findProperty("devrigCoordinatesFile") as String?)
        ?: "website/installer/devrig-coordinates.json").absolutePath

    inputs.file(jdkCoords)
    inputs.file(devrigCoords)
    inputs.property("version", project.version.toString())
    outputs.dir(outDir)

    args(
        "--out-dir", outDir,
        "--jdk-coordinates", jdkCoords,
        "--devrig-coordinates", devrigCoords,
        "--version", project.version.toString(),
    )
    doFirst { logger.lifecycle("[installer-gen] generateInstaller -> $outDir") }
}
