plugins {
    `java-library`
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":closeable-stack"))
    // api so test-helper consumers (test-integration, test-experiments, ij-plugin
    // integrationTest, prompts, installer-gen) keep seeing the process-runner types
    // they previously imported from this module.
    api(project(":process-util"))
    implementation(project(":ai-agents"))
    implementation(project(":agent-output-filter"))
    implementation(project(":prompts"))
    // PidMarker / IdeInfo / PluginInfo for the test-only fake marker file.
    // :mcp-steroid-server is plain JVM Kotlin (no com.intellij imports), so it
    // is safe to pull into test-helper's main classpath.
    implementation(project(":mcp-steroid-server"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // opentest4j + JUnit 4 on the main classpath so AIAgentCompanion.create() can
    // skip tests when an API key is missing on CI. JUnit 4's AssumptionViolatedException
    // is needed because CliClaudeIntegrationTest uses BasePlatformTestCase (JUnit 4 runner)
    // which doesn't recognize opentest4j's TestAbortedException as a skip signal.
    //
    // Test-helper deliberately does NOT pull in JUnit Jupiter on the main
    // classpath — consumers of test-helper use multiple JUnit versions, and
    // forcing one would break some of them. Helpers that need to fail a test
    // throw plain `AssertionError` (which every JUnit version surfaces as a
    // failure).
    implementation("org.opentest4j:opentest4j:1.3.0")
    implementation("junit:junit:4.13.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
