import com.jonnyzzz.mcpSteroid.gradle.configurePathingJarClasspath
import de.undercouch.gradle.tasks.download.Download
import org.gradle.kotlin.dsl.register

plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("de.undercouch.download")
}

repositories {
    mavenCentral()
}

// Version constants - tessdata version should be compatible with tesseract version
val tess4jVersion = "5.17.0"
val lept4jVersion = "1.21.1"
val tesseractPlatformVersion = "5.5.1-1.5.12"
val leptonicaPlatformVersion = "1.85.0-1.5.12"
val tessdataVersion = "4.1.0" // tessdata 4.1.0 is compatible with Tesseract 4.x and 5.x

dependencies {
    implementation(project(":ocr-common"))
    implementation("net.sourceforge.tess4j:tess4j:$tess4jVersion") {
        exclude(group = "net.sourceforge.lept4j", module = "lept4j")
    }
    implementation("net.sourceforge.lept4j:lept4j:$lept4jVersion")
    implementation("org.bytedeco:tesseract-platform:$tesseractPlatformVersion")
    implementation("org.bytedeco:leptonica-platform:$leptonicaPlatformVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "ocr-tesseract"
    mainClass.set("com.jonnyzzz.mcpSteroid.ocr.app.OcrCliKt")
}

// Pathing JAR for the launcher classpath (shared buildSrc logic) — keeps the Windows `set CLASSPATH=…`
// line short regardless of install-path depth, the same way devrig does.
configurePathingJarClasspath()

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    // Run against the installed distribution — matches production deployment and
    // ensures native libraries (Tesseract/Leptonica) are in the correct directory
    // structure for JNA/JavaCPP to load them on all platforms.
    dependsOn(tasks.installDist)

    // OCR smoke tests load native Tesseract/Leptonica libraries via JavaCPP + JNA.
    // On Windows, the JNA DLL loading requires the full installed distribution
    // structure (not the flat Gradle test classpath) — the tests fail with
    // UnsatisfiedLinkError. Disable the task on Windows; OCR on Windows is tested
    // via ij-plugin:test (OcrProcessClientTest) through the installed plugin.
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    enabled = !isWindows
    doFirst {
        val installDir = tasks.installDist.get().destinationDir
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val launcher = if (isWindows) {
            installDir.resolve("bin/ocr-tesseract.bat")
        } else {
            installDir.resolve("bin/ocr-tesseract")
        }
        systemProperty("ocr.test.launcher", launcher.absolutePath)
        systemProperty("ocr.test.install.dir", installDir.absolutePath)
    }
}

// Tessdata download configuration
val tessdataDownloadDir = layout.buildDirectory.dir("tessdata-download/$tessdataVersion")
val tessdataDir = layout.buildDirectory.dir("tessdata-data")
val downloadConnectTimeoutMs = 30_000
val downloadReadTimeoutMs = 15 * 60_000
val downloadRetryCount = 5

fun Download.configureReliableDownload() {
    onlyIfModified(true)
    connectTimeout(downloadConnectTimeoutMs)
    readTimeout(downloadReadTimeoutMs)
    retries(downloadRetryCount)
    // Used for atomic download under name "${dest}.part" and rename to the final name only on success.
    // necessary for not using corrupted downloaded artifacts
    tempAndMove(true)
}

// Download tessdata files
val downloadTessdata by tasks.registering {
    outputs.dir(tessdataDownloadDir)
}

listOf(
    "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion/eng.traineddata",
    "https://github.com/tesseract-ocr/tessdata/raw/$tessdataVersion/osd.traineddata",
).forEach { url ->
    val fileName = url.substringAfterLast("/")
    val task = tasks.register<Download>("download_" + fileName.substringBefore(".")) {
        src(url)
        dest(tessdataDownloadDir)
        configureReliableDownload()
        // Skip entirely when the file already exists — avoids a GitHub round-trip on every
        // test run. The de.undercouch.download plugin always registers upToDateWhen{false}
        // internally, so onlyIf is the only way to truly skip the task. Stale downloads are
        // evicted by `clean` or by deleting the file manually.
        onlyIf { !tessdataDownloadDir.get().asFile.resolve(fileName).exists() }
    }
    downloadTessdata.configure { dependsOn(task) }
}

// Extract ALL Windows native DLLs into a single native/ directory in the distribution.
// This includes:
//   - libtesseract551.dll from tess4j (MSVC-compiled OCR engine)
//   - libleptonica1850.dll from lept4j (MSVC-compiled image processing)
//   - MSVC runtime DLLs from JavaCPP (msvcp140.dll, vcruntime140.dll, ucrtbase.dll, etc.)
// Having everything in one directory simplifies native library loading — just set
// jna.library.path to native/ and the Windows DLL loader resolves all dependencies.
// See: https://learn.microsoft.com/en-us/cpp/windows/determining-which-dlls-to-redistribute
val extractWindowsNatives by tasks.registering(Copy::class) {
    group = "build"
    description = "Extract all Windows native DLLs for distribution"
    val cp = configurations.runtimeClasspath.get().files

    // Tess4J: libtesseract551.dll
    cp.find { it.name.startsWith("tess4j") }?.let { jar ->
        from(zipTree(jar)) {
            include("win32-x86-64/*.dll")
            eachFile { path = name }
        }
    }

    // lept4j: libleptonica1850.dll
    // NOTE: tess4j 5.17.0's libtesseract551.dll imports libleptonica1860.dll (version mismatch
    // between tess4j and lept4j). Create a copy with the expected name so the DLL loader finds it.
    cp.find { it.name.startsWith("lept4j") }?.let { jar ->
        from(zipTree(jar)) {
            include("win32-x86-64/*.dll")
            eachFile { path = name }
        }
        // Duplicate under the name tesseract actually imports
        from(zipTree(jar)) {
            include("win32-x86-64/libleptonica1850.dll")
            eachFile { path = "libleptonica1860.dll" }
        }
    }

    // JavaCPP: MSVC runtime DLLs (VC++ 2015-2022 Redistributable)
    cp.find { it.name.contains("javacpp") && it.name.contains("windows-x86_64") }?.let { jar ->
        from(zipTree(jar)) {
            include("org/bytedeco/javacpp/windows-x86_64/msvcp140*.dll")
            include("org/bytedeco/javacpp/windows-x86_64/vcruntime140*.dll")
            include("org/bytedeco/javacpp/windows-x86_64/ucrtbase.dll")
            include("org/bytedeco/javacpp/windows-x86_64/concrt140.dll")
            include("org/bytedeco/javacpp/windows-x86_64/vcomp140.dll")
            include("org/bytedeco/javacpp/windows-x86_64/libomp140.x86_64.dll")
            include("org/bytedeco/javacpp/windows-x86_64/api-ms-win-*.dll")
            eachFile { path = name }
        }
    }

    into(layout.buildDirectory.dir("windows-natives"))
    includeEmptyDirs = false
}

// Include tessdata and MSVC runtime in the distribution
distributions {
    main {
        contents {
            from(downloadTessdata) {
                into("tessdata")
            }
            from(extractWindowsNatives) {
                into("native")
            }
        }
    }
}

// Create a configuration that exposes the installed distribution
val installDistElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "install-dist"))
    }
}

artifacts {
    add(installDistElements.name, tasks.installDist.map { it.destinationDir }) {
        builtBy(tasks.installDist)
    }
}
