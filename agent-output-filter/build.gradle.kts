plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}

application {
    applicationName = "agent-output-filter"
    mainClass.set("com.jonnyzzz.mcpSteroid.filter.FilterCliKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
}

val executableDistribution = configurations.create("executableDistribution") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(executableDistribution.name, tasks.distZip)
}
