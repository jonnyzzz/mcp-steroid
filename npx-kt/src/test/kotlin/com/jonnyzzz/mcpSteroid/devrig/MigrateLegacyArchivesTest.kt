/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrateLegacyArchivesTest {

    @Test
    fun `migrateLegacyArchives moves old archive files into downloads and is idempotent`(
        @TempDir tempDir: Path,
    ) {
        val paths = HomePaths(tempDir.resolve("home"))
        val legacyDir = paths.cachesDir.resolve("_archives")
        val archiveName = "ideaIC-2025.3.3.tar.gz"
        Files.createDirectories(legacyDir)
        Files.writeString(legacyDir.resolve(archiveName), "archive bytes")

        migrateLegacyArchives(paths)
        migrateLegacyArchives(paths)

        val migratedArchive = paths.downloadsDir.resolve(archiveName)
        assertTrue(Files.isRegularFile(migratedArchive), "archive should move into downloads/")
        assertEquals("archive bytes", Files.readString(migratedArchive))
        assertTrue(!Files.exists(legacyDir), "empty legacy archive directory should be deleted")
    }
}
