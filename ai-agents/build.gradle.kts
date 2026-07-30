plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":mcp-core"))
}

kotlin {
    jvmToolchain(25)
}
