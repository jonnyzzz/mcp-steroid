plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    // Pinned to the IDE-bundled kotlinx runtime version (root gradle.properties) because this module
    // links into the :ij-plugin runtime classpath, where the IDE classloader serves kotlinx jars.
    val kotlinxSerialization = providers.gradleProperty("mcp.kotlinx.serialization.version").get()
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerialization")

    // DevrigVersion appears in UpdateCoordination.gc()'s public signature.
    api(project(":mcp-core"))

    // StdioMcpCommand is returned by DevrigUserLauncher.invocation.
    api(project(":ai-agents"))

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
