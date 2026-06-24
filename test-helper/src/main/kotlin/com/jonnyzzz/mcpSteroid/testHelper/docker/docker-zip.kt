package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File


fun ContainerDriver.deployZipAndUnpack(zipFile: File, guestUnpackDir: String) {
    deployZipAndUnpackImpl(this, zipFile, guestUnpackDir)
}

private fun deployZipAndUnpackImpl(driver: ContainerDriver, zipFile: File, guestUnpackDir: String) {
    require(zipFile.isFile()) { "zip does not exist: $zipFile" }

    // Staging location is container-local — NOT on the /mcp-run-dir bind
    // mount. Keeps plugin.zip (~185 MB) out of the TC artifact zip.
    val containerTempDir = "/home/agent/ide-plugin-staging"
    val containerTempZip = "$containerTempDir/${zipFile.name}"
    println("Deploying plugin to container: $zipFile")

    driver.mkdirs(guestUnpackDir)
    driver.mkdirs(containerTempDir)
    // Clear any previous plugin tree before unzipping. When we reuse a
    // warmed snapshot image, the previous plugin version is baked into
    // /home/agent/ide-plugins — unzipping over it leaves a mixture of
    // old + new plugin files which IDEA happily picks up and crashes on.
    driver.startProcessInContainer {
        this
            .args("bash", "-c", "rm -rf '$guestUnpackDir'/* '$containerTempDir'/*")
            .timeoutSeconds(30)
            .quietly()
            .description("clear $guestUnpackDir before plugin deploy")
    }.assertExitCode(0) { "Failed to clear $guestUnpackDir" }
    driver.copyToContainer(zipFile, containerTempZip)
    driver.startProcessInContainer {
        this
            .args("unzip", "-o", containerTempZip)
            .workingDirInContainer(guestUnpackDir)
            .timeoutSeconds(60)
            .quietly()
            .description("unzip plugin to $guestUnpackDir")
    }.assertExitCode(0) { "$containerTempZip failed to unpack: $zipFile" }
    // Drop the staged zip — the unpacked tree is the only thing IDEA needs.
    driver.startProcessInContainer {
        this
            .args("rm", "-f", containerTempZip)
            .timeoutSeconds(10)
            .quietly()
            .description("remove staged $containerTempZip")
    }.assertExitCode(0) { "Failed to remove $containerTempZip" }
}
