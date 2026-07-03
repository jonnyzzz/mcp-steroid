/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins the #212 acceptance criterion in a real platform test environment: an
 * in-process test run must classify as UNIT_TEST — never as HEADLESS — so the
 * plugin's own test suite gets no headless WARN and no client notice appended.
 * (The test framework sets the Application test flag in its constructor without
 * the `idea.is.unit.test` system property, which is exactly the case a
 * property-based detector would misclassify.)
 */
class IdeRunModePlatformTest : BasePlatformTestCase() {
    fun testInProcessTestEnvironmentClassifiesAsUnitTest() {
        assertEquals(IdeRunMode.UNIT_TEST, detectIdeRunMode())
    }
}
