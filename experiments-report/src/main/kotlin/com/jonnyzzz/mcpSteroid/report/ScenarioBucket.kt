package com.jonnyzzz.mcpSteroid.report

/**
 * Dashboard grouping for scenarios, in DISPLAY ORDER: the IDE-power experiments lead (they are the
 * dashboard's headline — semantic tasks where the IDE's PSI answer is exact while grep's is
 * incomplete), the debugger demos follow, and the DPAIA fix-the-build arena sits under its own
 * dedicated heading instead of interleaving with everything else.
 */
enum class ScenarioBucket(val title: String) {
    IDE_SEMANTIC("IDE semantic power — PSI vs grep"),
    DEBUGGER("Debugger"),
    DPAIA("DPAIA arena — fix the build"),
    OTHER("Other experiments"),
}

/** Bucket for one scenario id, by its stable prefix (`keycloak__…`, `dpaia__…`, `debugger__…`). */
fun scenarioBucket(scenario: String): ScenarioBucket = when {
    scenario.startsWith("keycloak__") || scenario.startsWith("youtrackdb__") -> ScenarioBucket.IDE_SEMANTIC
    scenario.startsWith("debugger__") -> ScenarioBucket.DEBUGGER
    scenario.startsWith("dpaia__") -> ScenarioBucket.DPAIA
    else -> ScenarioBucket.OTHER
}
