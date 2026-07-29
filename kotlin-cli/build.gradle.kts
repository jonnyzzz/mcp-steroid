import de.undercouch.gradle.tasks.download.Download
import java.security.MessageDigest

plugins {
    kotlin("jvm")
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

// --- kotlinc download and distribution ---

val kotlincVersion = "2.3.20"
val kotlincUrl = "https://github.com/JetBrains/kotlin/releases/download/v${kotlincVersion}/kotlin-compiler-${kotlincVersion}.zip"
val kotlincSha256Url = "$kotlincUrl.sha256"
val kotlincDownloadDir = layout.buildDirectory.dir("kotlinc-zip/$kotlincVersion")
val kotlincDir = layout.buildDirectory.dir("kotlinc-unpack")

fun Download.configureReliableDownload() {
    onlyIfModified(true)
    connectTimeout(30_000)
    readTimeout(15 * 60_000)
    retries(5)
    tempAndMove(true)
}

val downloadKotlinc = tasks.register("downloadKotlinc") {
    group = "kotlinc"
    outputs.dir(kotlincDir)

    doLast {
        val zipFileName = "kotlin-compiler-${kotlincVersion}.zip"
        val shaFileName = "$zipFileName.sha256"

        val zip = kotlincDownloadDir.get().file(zipFileName).asFile
        val shaFile = kotlincDownloadDir.get().file(shaFileName).asFile

        check(zip.isFile) { "Missing downloaded kotlinc archive: $zip" }
        check(shaFile.isFile) { "Missing downloaded kotlinc checksum: $shaFile" }

        val sha256 = shaFile
            .readText()
            .trim()
            .substringBefore(' ')

        val actualSha256 = MessageDigest.getInstance("SHA-256").run {
            update(zip.readBytes())
            digest().toHexString()
        }

        check(actualSha256 == sha256) {
            "Actual:\n${actualSha256}\nExpected\n${sha256}"
        }

        sync {
            into(kotlincDir)
            from(zipTree(zip))
        }
    }
}

listOf(kotlincUrl, kotlincSha256Url).forEach { url ->
    val fileName = url.substringAfterLast("/")
    val task = tasks.register<Download>("downloadKotlinc_" + url.substringAfterLast(".")) {
        group = "kotlinc"
        src(url)
        dest(kotlincDownloadDir)
        configureReliableDownload()
        onlyIf { !kotlincDownloadDir.get().asFile.resolve(fileName).exists() }
    }
    downloadKotlinc.configure { dependsOn(task) }
}

// Consumable configuration — exposes the unpacked kotlinc directory as artifact
val kotlincDistElements = configurations.create("kotlincDistElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "kotlinc-dist"))
    }
}

artifacts {
    add(kotlincDistElements.name, kotlincDir) {
        builtBy(downloadKotlinc)
    }
}
