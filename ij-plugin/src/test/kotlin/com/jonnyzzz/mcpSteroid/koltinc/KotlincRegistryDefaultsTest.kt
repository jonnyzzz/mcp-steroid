/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlincRegistryDefaultsTest : BasePlatformTestCase() {
    fun testDefaultKotlincParametersTargetKotlin23() {
        // 2.3 = the Kotlin bundled by the oldest supported IDE (sinceBuild=261 ships
        // kotlin-stdlib/reflect 2.3.20, metadata 2.3.0). Bump this pin together with
        // sinceBuild: the pin must stay at the FLOOR of the supported IDE range so a
        // script compiles identically on every IDE and its metadata stays readable by
        // the oldest bundled kotlin-reflect.
        assertEquals(
            "-language-version 2.3 -api-version 2.3",
            Registry.stringValue("mcp.steroid.kotlinc.parameters")
        )
    }
}
