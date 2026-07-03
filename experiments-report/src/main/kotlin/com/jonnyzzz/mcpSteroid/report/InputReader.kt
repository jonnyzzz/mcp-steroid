package com.jonnyzzz.mcpSteroid.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads an input directory tree into a merged list of [AgentRun]s.
 *
 * Two layouts, one reader:
 *  - **CI collector layout** — a `builds/` directory with one sub-folder per build config
 *    (`builds/<configId>/log.txt`, `…/runs/<mode>/agent.ndjson`, optional `…/summaries/…json`,
 *    optional `…/meta.json`). Processed per build folder, so NDJSON metrics attach to the right
 *    (scenario, agent) — taken from the build's own log — keyed by mode-from-path.
 *  - **Local test-output layout** — a flat tree of summary JSONs and the arena log. Summary JSONs are
 *    self-identifying; NDJSON is skipped here because the summary JSON already carries its metrics.
 *
 * Within a file:
 *  - a `.json` named like `dpaia-arena-run-…` → [RunSummaryJsonParser]
 *  - a `.log` / `.txt` containing `[ARENA]` → [ArenaLogParser]
 *  - a `.ndjson` (build layout only) → [NdjsonParser], tagged with the build's (scenario, agent) and the
 *    mode parsed from its path
 *
 * Parse failures on a single file are swallowed so one malformed input never sinks the report. Runs are
 * merged by (scenario, agent, mode) via [mergeRuns].
 */
object InputReader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(root: File): List<AgentRun> = readAll(root).latest

    /**
     * Both views of the cache:
     *  - [latest] — per (scenario, agent, mode) only the LATEST build's merged run; the primary
     *    comparison is built exclusively from these.
     *  - [allBuilds] — one merged run per (scenario, agent, mode, build): every cached build,
     *    including superseded ones, contributes one attempt to the run history.
     */
    data class CollectedRuns(val latest: List<AgentRun>, val allBuilds: List<AgentRun>)

    fun readAll(root: File): CollectedRuns {
        val buildsDir = File(root, "builds")
        if (!buildsDir.isDirectory) {
            val flat = mergeRuns(readFlat(root))
            return CollectedRuns(latest = flat, allBuilds = flat)
        }
        // Source-merge stays WITHIN one build folder: each cached build yields its own merged run
        // per (scenario, agent, mode), so a superseded build survives as a distinct history attempt
        // and can never leak fields across builds.
        val perBuild = buildsDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
            .flatMap { mergeRuns(readBuildFolder(it)) }
        return CollectedRuns(
            latest = mergeRuns(latestBuildOnly(perBuild)),
            allBuilds = perBuild,
        )
    }

    /**
     * Keep, per (scenario, agent, mode), only the runs of the LATEST build. The fetch cache is
     * incremental — superseded builds' folders stay on disk — and [mergeRuns] merges *sources of one
     * run*, so without this cut a stale build's crashed run leaks fields into (or entirely shadows)
     * the newest build's data. Observed on KeycloakRename_Claude 2026-07-02: a superseded 429-crash
     * leg (2s, 0/0 tokens) displaced the fresh successful run on the rendered dashboard. Runs without
     * a buildId (flat/local layout) are never dropped.
     */
    private fun latestBuildOnly(runs: List<AgentRun>): List<AgentRun> {
        val latest = HashMap<Triple<String, String, McpMode>, Long>()
        for (r in runs) {
            val id = r.buildId ?: continue
            latest.merge(Triple(r.scenario, r.agent, r.mode), id, ::maxOf)
        }
        return runs.filter { r ->
            r.buildId == null || r.buildId == latest[Triple(r.scenario, r.agent, r.mode)]
        }
    }

    /** Every collected build's `meta.json` (collector layout only) — for the coverage view. */
    fun readBuildMetas(root: File): List<BuildMeta> {
        val buildsDir = File(root, "builds").takeIf { it.isDirectory } ?: return emptyList()
        return buildsDir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val meta = File(dir, "meta.json").takeIf { it.isFile } ?: return@mapNotNull null
            val o = runCatching { json.parseToJsonElement(meta.readText()).jsonObject }.getOrNull() ?: return@mapNotNull null
            BuildMeta(
                buildConfigId = o["buildConfigId"]?.jsonPrimitive?.contentOrNull ?: dir.name,
                buildId = o["buildId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                scenario = o["scenario"]?.jsonPrimitive?.contentOrNull ?: "",
                agent = o["agent"]?.jsonPrimitive?.contentOrNull ?: "",
                status = o["status"]?.jsonPrimitive?.contentOrNull,
            )
        }.sortedBy { it.buildConfigId }
    }

    /** Flat layout: only the self-identifying sources (summary JSON + arena log). */
    private fun readFlat(root: File): List<AgentRun> = buildList {
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            runCatching { addAll(parseSelfIdentifying(f)) }
        }
    }

    private fun parseSelfIdentifying(f: File): List<AgentRun> {
        val name = f.name.lowercase()
        return when {
            name.endsWith(".json") && name.contains("dpaia-arena-run") -> listOf(RunSummaryJsonParser.parse(f.readText()))
            name.endsWith(".log") || name.endsWith(".txt") -> {
                val text = f.readText()
                if (text.contains("[ARENA]")) ArenaLogParser.parse(text) else emptyList()
            }
            else -> emptyList()
        }
    }

    /** One build config's folder: log + summaries are self-identifying; ndjson is attached via build context. */
    private fun readBuildFolder(dir: File): List<AgentRun> {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val selfId = files.flatMap { f -> runCatching { parseSelfIdentifying(f) }.getOrDefault(emptyList()) }

        // (scenario, agent) for this build: the log/summary tells us; meta.json is the fallback.
        val ctx = selfId.firstOrNull()?.let { it.scenario to it.agent } ?: readMeta(dir)

        // Every run parsed inside this folder belongs to THIS build — stamp its identity so
        // latest-build selection can tell fresh runs from a superseded build's leftovers. The folder
        // name (`<configId>__<buildId>`) is the fallback when meta.json is missing.
        val (buildConfigId, buildId) = readBuildIdentity(dir)
        // …and its finish date (collector meta.json `finishDate` — ISO-8601 or TeamCity format),
        // the recency signal for the run-history weighting. Old caches without it stay undated.
        val finishedAt = parseFinishDate(readMetaField(dir, "finishDate"))

        val ndjsonRuns = if (ctx == null) emptyList() else files
            .filter { it.name.lowercase().endsWith(".ndjson") }
            .mapNotNull { f ->
                val mode = modeFromPath(f.path) ?: return@mapNotNull null
                val m = runCatching { NdjsonParser.parse(f.readText()) }.getOrNull() ?: return@mapNotNull null
                AgentRun(
                    scenario = ctx.first, agent = ctx.second, mode = mode,
                    model = m.model, agentVersion = m.agentVersion,
                    contextWindow = m.contextWindow, maxOutputTokens = m.maxOutputTokens,
                    inputTokens = m.inputTokens, outputTokens = m.outputTokens,
                    cacheReadTokens = m.cacheReadTokens, cacheCreationTokens = m.cacheCreationTokens,
                    costUsd = m.costUsd, numTurns = m.numTurns,
                    toolCalls = m.toolCalls,
                    execCodeCalls = m.toolCalls.entries.firstOrNull { it.key.contains("steroid_execute_code") }?.value,
                )
            }

        return (selfId + ndjsonRuns).map { r ->
            r.copy(
                buildConfigId = r.buildConfigId ?: buildConfigId,
                buildId = r.buildId ?: buildId,
                finishedAt = r.finishedAt ?: finishedAt,
            )
        }
    }

    /** One string field out of the folder's meta.json, or null when the file/field is absent. */
    private fun readMetaField(dir: File, field: String): String? {
        val meta = File(dir, "meta.json").takeIf { it.isFile } ?: return null
        val o = runCatching { json.parseToJsonElement(meta.readText()).jsonObject }.getOrNull() ?: return null
        return o[field]?.jsonPrimitive?.contentOrNull
    }

    /** (buildConfigId, buildId) from meta.json, falling back to the `<configId>__<buildId>` dir name. */
    private fun readBuildIdentity(dir: File): Pair<String?, Long?> {
        val meta = File(dir, "meta.json").takeIf { it.isFile }
        if (meta != null) {
            val o = runCatching { json.parseToJsonElement(meta.readText()).jsonObject }.getOrNull()
            if (o != null) {
                val cfg = o["buildConfigId"]?.jsonPrimitive?.contentOrNull
                val bid = o["buildId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                if (cfg != null || bid != null) return cfg to bid
            }
        }
        val name = dir.name
        val sep = name.lastIndexOf("__")
        if (sep > 0) {
            val bid = name.substring(sep + 2).toLongOrNull()
            if (bid != null) return name.substring(0, sep) to bid
        }
        return name to null
    }

    /** mcp / none from a path segment (run dir names like `runs/mcp` or `run-…-none`). */
    private fun modeFromPath(path: String): McpMode? {
        val p = path.lowercase()
        return when {
            p.contains("none") || p.contains("without") -> McpMode.WITHOUT
            p.contains("mcp") -> McpMode.WITH
            else -> null
        }
    }

    private fun readMeta(dir: File): Pair<String, String>? {
        val meta = File(dir, "meta.json").takeIf { it.isFile } ?: return null
        val o = runCatching { json.parseToJsonElement(meta.readText()).jsonObject }.getOrNull() ?: return null
        val scenario = o["scenario"]?.jsonPrimitive?.contentOrNull ?: return null
        val agent = o["agent"]?.jsonPrimitive?.contentOrNull ?: return null
        return scenario to agent
    }
}
