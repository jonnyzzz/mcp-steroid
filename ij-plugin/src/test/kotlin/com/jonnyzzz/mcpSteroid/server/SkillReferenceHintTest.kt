/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.executionSuggestionService

class SkillReferenceHintTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testHintForProtectedApplicationConfigurationConstructor() {
        val compilerError = """
            input.kt:39:22: error: cannot access 'constructor(p0: String!, p1: Project, p2: ConfigurationFactory): ApplicationConfiguration': it is protected in 'com.intellij.execution.application.ApplicationConfiguration'.
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should recommend modern run configuration creation APIs:\n$hint",
            hint.contains("RunManager.createConfiguration")
        )
        assertTrue(
            "Hint should direct agents away from direct ApplicationConfiguration constructor usage:\n$hint",
            hint.contains("ApplicationConfiguration")
        )
    }

    fun testHintForPsiFileVirtualFileMismatch() {
        val compilerError = """
            input.kt:45:55: error: argument type mismatch: actual type is 'PsiFile', but 'VirtualFile' was expected.
            input.kt:40:29: error: unresolved reference 'path'.
            input.kt:71:31: error: unresolved reference 'url'.
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should suggest a VirtualFile-oriented search/read pattern:\n$hint",
            hint.contains("FilenameIndex.getVirtualFilesByName")
        )
        assertTrue(
            "Hint should explain how to convert PsiFile to VirtualFile safely:\n$hint",
            hint.contains("psiFile.virtualFile")
        )
    }

    fun testHintForDeprecatedFindFilesHelper() {
        val compilerError = """
            input.kt:24:17: error: unresolved reference 'findFiles'.
            input.kt:27:45: error: unresolved reference 'contentsToByteArray'.
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should point to currently supported file discovery helpers:\n$hint",
            hint.contains("findProjectFiles")
        )
        assertTrue(
            "Hint should suggest VirtualFile loading pattern when helper is unavailable:\n$hint",
            hint.contains("VfsUtilCore")
        )
    }

    fun testHintForLegacyExecuteSuspendLabel() {
        val compilerError = """
            input.kt:28:15: error: unresolved label.
                    return@executeSuspend
                          ^^^^^^^^^^^^^^^
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(compilerError)
        assertTrue(
            "Hint should mention both legacy execute wrapper labels:\n$hint",
            hint.contains("return@executeSteroidCode or return@executeSuspend")
        )
        assertTrue(
            "Hint should recommend plain return in script body context:\n$hint",
            hint.contains("Use plain return")
        )
    }

    fun testEmptyOutputHintFiresOnSuccessfulSilentScript() {
        val service = project.executionSuggestionService
        val suggestions = service.generateSuggestions(
            isFailed = false,
            errorMessages = emptyList(),
            userOutputCount = 0,
        )
        assertEquals(
            "Successful but silent script must yield exactly one hint:\n$suggestions",
            1, suggestions.size
        )
        val hint = suggestions.single()
        assertTrue(
            "Hint must call out the missing println(...) explicitly:\n$hint",
            hint.contains("println(value)")
        )
        assertTrue(
            "Hint must mention printJson for structured data:\n$hint",
            hint.contains("printJson(value)")
        )
        assertTrue(
            "Hint must explain that the last expression is not auto-printed:\n$hint",
            hint.contains("NOT auto-printed")
        )
    }

    fun testEmptyOutputHintStaysQuietWhenScriptPrinted() {
        val service = project.executionSuggestionService
        val suggestions = service.generateSuggestions(
            isFailed = false,
            errorMessages = emptyList(),
            userOutputCount = 3,
        )
        assertTrue(
            "Successful script with output must produce no suggestions:\n$suggestions",
            suggestions.isEmpty()
        )
    }

    fun testEmptyOutputHintIsNotEmittedOnFailure() {
        val service = project.executionSuggestionService
        // Failed execution that ALSO printed nothing must surface the failure-side hint,
        // never the empty-output one (the failure is the actionable signal).
        val suggestions = service.generateSuggestions(
            isFailed = true,
            errorMessages = listOf("Read access is allowed from inside read-action only"),
            userOutputCount = 0,
        )
        assertEquals(
            "Failure path must produce exactly the failure hint:\n$suggestions",
            1, suggestions.size
        )
        assertFalse(
            "Failure path must not surface the empty-output hint:\n${suggestions.single()}",
            suggestions.single().contains("NOT auto-printed")
        )
        assertTrue(
            "Failure hint must still steer toward readAction:\n${suggestions.single()}",
            suggestions.single().contains("readAction")
        )
    }

    fun testReadActionHintEnumeratesCommonWrapTargets() {
        val hint = project.executionSuggestionService.computeHint(
            "Read access is allowed from inside read-action only (see Application.runReadAction())"
        )
        for (api in listOf(
            "FilenameIndex.*",
            "PsiManager.findFile(vf)",
            "psiFile.text",
            "vf.children",
            "FileDocumentManager.getDocument(vf)",
            "ProjectRootManager.contentRoots",
            "ChangeListManager.allChanges",
        )) {
            assertTrue(
                "Read-action hint must enumerate $api as a wrap target:\n$hint",
                hint.contains(api)
            )
        }
    }

    fun testReadActionHintLinksToThreadingArticle() {
        val hint = project.executionSuggestionService.computeHint(
            "Read access is allowed from inside read-action only"
        )
        assertTrue(
            "Read-action hint must surface the coding-with-intellij-threading article URI:\n$hint",
            hint.contains("mcp-steroid://skill/coding-with-intellij-threading")
        )
        assertTrue(
            "Read-action hint must remind that the wrap is required on EVERY script:\n$hint",
            hint.contains("EVERY script") || hint.contains("every script")
        )
    }

    fun testGenerateSuggestionsBackwardCompatWhenUserOutputCountUnknown() {
        val service = project.executionSuggestionService
        // The default -1 must keep the old behavior: success + no error => no hint.
        val suggestions = service.generateSuggestions(
            isFailed = false,
            errorMessages = emptyList(),
        )
        assertTrue(
            "Default userOutputCount must NOT trigger the empty-output hint:\n$suggestions",
            suggestions.isEmpty()
        )
    }

    fun testHintForIndexNotReadyException() {
        val error = """
            com.intellij.openapi.project.IndexNotReadyException: Please change caller according to com.intellij.openapi.project.IndexNotReadyException documentation
        """.trimIndent()

        val hint = project.executionSuggestionService.computeHint(error)
        assertTrue(
            "Hint should recommend smartReadAction for indexed PSI work:\n$hint",
            hint.contains("smartReadAction")
        )
        assertTrue(
            "Hint should recommend Observation.awaitConfiguration after project configuration:\n$hint",
            hint.contains("Observation.awaitConfiguration")
        )
        assertTrue(
            "Hint should explain that waitForSmartMode is point-in-time only:\n$hint",
            hint.contains("point-in-time")
        )
    }

    fun testHintForBareNullPointerException() {
        // #156: the messageless-NPE summary produced by ScriptExecutor must get the
        // lookup-contract hint (agents' `!!` on a null findProjectFile lookup).
        val error = "Unexpected error during execution: NullPointerException (no message) — see stack trace above"

        val hint = project.executionSuggestionService.computeHint(error)
        assertTrue(
            "Hint should explain the null-lookup / `!!` pattern:\n$hint",
            hint.contains("null lookup")
        )
        assertTrue(
            "Hint should teach the ?: error(...) idiom naming the path:\n$hint",
            hint.contains("error(")
        )
        assertTrue(
            "Hint should reference the VFS skill resource:\n$hint",
            hint.contains("mcp-steroid://skill/coding-with-intellij-vfs")
        )
    }

    fun testBreakpointNpeStillWinsOverGenericNpeHint() {
        // Precedence: the debugger-specific NPE branch must keep matching first.
        val error = """Cannot invoke "com.intellij.debugger.JavaLineBreakpointProperties.getLambdaOrdinal()" because "props" is null"""

        val hint = project.executionSuggestionService.computeHint(error)
        assertTrue(
            "Debugger-specific NPE hint must win over the generic NPE hint:\n$hint",
            hint.contains("XDebuggerUtil.toggleLineBreakpoint")
        )
    }

    fun testNpeWithRealMessageGetsNoLookupHint() {
        // An NPE that carries its own message is its own diagnostic — the lookup-contract
        // hint must NOT fire (it matches only the "(no message)" summary shape).
        val error = """Unexpected error during execution: java.lang.NullPointerException: Cannot read field "foo" because "bar" is null"""

        val hint = project.executionSuggestionService.computeHint(error)
        assertFalse(
            "Messaged NPE must not get the lookup-contract hint:\n$hint",
            hint.contains("null lookup")
        )
    }
}
