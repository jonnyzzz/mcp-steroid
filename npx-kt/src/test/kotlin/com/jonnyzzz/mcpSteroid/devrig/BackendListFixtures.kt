/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Files

fun backendFixture(
    homePaths: HomePaths,
    id: String,
    productKey: String,
    productCode: String,
    version: String,
) {
    val dir = homePaths.backendDir(id)
    Files.createDirectories(dir)
    writeDescriptor(
        descriptorPath(dir),
        BackendDescriptor(
            id = id,
            productKey = productKey,
            productCode = productCode,
            version = version,
            buildNumber = "$productCode-253.1",
            bundleDirName = "bundle-$id",
            launcherPath = "bin/${productKey.substringBefore('-')}.sh",
            downloadedAt = "2026-05-14T21:00:00Z",
        ),
    )
}

class FakeProcessInspector(
    private val alivePids: Set<Long> = emptySet(),
    private val snapshots: Map<Long, ProcessSnapshot> = emptyMap(),
) : ManagedProcessInspector {
    override fun isAlive(pid: Long): Boolean = pid in alivePids

    override fun allProcesses(): List<ProcessSnapshot> = snapshots.values.toList()

    override fun snapshot(pid: Long): ProcessSnapshot? = snapshots[pid]
}
