plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.slf4j:slf4j-api:2.0.13")

    val kotlinxSerialization = providers.gradleProperty("mcp.kotlinx.serialization.version").get()
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerialization")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:$kotlinxSerialization")
    implementation(libs.kotlinx.coroutines.core)

    // MCP protocol types, JSON-RPC helpers
    api(project(":mcp-core"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
