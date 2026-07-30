/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-mapping guards for the direct in-IDE #155 surface
 * ([SelfBackendDescription.toListProjectsResponse]): the `backends[]` self entry is UNCONDITIONAL —
 * a fresh IDE with zero open projects must still be identifiable — and `projects[]` is sorted by
 * `project_name` regardless of `ProjectManager` open-order. (The platform-backed integration
 * fixture opens exactly one project, so these two rows are pinned here at the unit level.)
 */
class SelfBackendResponseMappingTest {
    private val intellij = IntelliJInfo(name = "IntelliJ IDEA 2026.1.3", version = "2026.1.3", build = "IU-261.25134.95")

    @Test
    fun `zero open projects still yield the unconditional backends self entry`() {
        val response = SelfBackendDescription(
            backendName = "iu-47qi79c1",
            projects = emptyList(),
            intellij = intellij,
        ).toListProjectsResponse()

        assertTrue(response.projects.isEmpty())
        assertEquals(listOf(BackendRef(backendName = "iu-47qi79c1", intellij = intellij)), response.backends)
    }

    @Test
    fun `projects are sorted by project_name regardless of open order`() {
        val zulu = ListedProject(projectName = "zulu-9fk2a0xq", name = "zulu", path = "/z", backendName = "iu-47qi79c1")
        val alpha = ListedProject(projectName = "alpha-8x1k2mq0", name = "alpha", path = "/a", backendName = "iu-47qi79c1")

        val response = SelfBackendDescription(
            backendName = "iu-47qi79c1",
            projects = listOf(zulu, alpha),
            intellij = intellij,
        ).toListProjectsResponse()

        assertEquals(listOf(alpha, zulu), response.projects)
        assertEquals(listOf(BackendRef(backendName = "iu-47qi79c1", intellij = intellij)), response.backends)
    }
}
