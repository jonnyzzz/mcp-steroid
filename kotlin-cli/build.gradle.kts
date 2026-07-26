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

val btaImplDecl = configurations.resolvable("kotlinBuildToolsImpl")
val btaImplClasspath = configurations.resolvable("kotlinBuildToolsImplClasspath") {
    extendsFrom(btaImplDecl)

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

dependencies {
    api(libs.kotlin.buildTools.api)
    api(libs.kotlinx.coroutines.core)

    btaImplDecl.name(libs.kotlin.buildTools.impl)
    btaImplDecl.name(libs.kotlin.buildTools.compat)

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
