/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.ide.TypeHierarchyPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeHierarchyPromptContractTest {
    @Test
    fun `exhaustive implementor recipe distinguishes interfaces and classes`() {
        val prompt = TypeHierarchyPromptArticle().readPayload(PromptsContext("IU", 262))

        for (fact in listOf(
            "ClassInheritorsSearch",
            "sub-interfaces",
            "abstract classes",
            "concrete classes",
            "!it.isInterface",
            "PsiModifier.ABSTRACT",
            "checkDeep=true",
            "anonymous/local",
            "qualifiedName",
            "subsTruncated",
        )) {
            assertTrue(fact in prompt, "type-hierarchy prompt must mention '$fact': $prompt")
        }
        assertFalse(
            Regex("""val (subInterfaces|abstractClasses|concreteClasses): List<String>""").containsMatchIn(prompt),
            "the capped hierarchy tree must not be followed by unbounded duplicate category lists: $prompt",
        )
    }
}
