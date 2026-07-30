@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

repositories {
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
    // Single version for -api/-compat/-impl: see the mcp.kotlinc.version comment
    // in gradle.properties (-impl IS the snippet compiler).
    val kotlincVersion = providers.gradleProperty("mcp.kotlinc.version").get()
    val kotlinxCoroutines = providers.gradleProperty("mcp.kotlinx.coroutines.version").get()

    api("org.jetbrains.kotlin:kotlin-build-tools-api:$kotlincVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutines")

    btaImplDecl.name("org.jetbrains.kotlin:kotlin-build-tools-impl:$kotlincVersion")
    btaImplDecl.name("org.jetbrains.kotlin:kotlin-build-tools-compat:$kotlincVersion")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

val copyForResourcesTask = tasks.register<Copy>("copyBtaImpForResources") {
    val btaImplLocation = layout.buildDirectory.dir("bta-impl-jars/BTA-IMPL")

    from(btaImplClasspath)
    into(btaImplLocation)
}

sourceSets.main.configure {
    resources.srcDir(
        copyForResourcesTask.map { it.destinationDir.parentFile }
    )
}
