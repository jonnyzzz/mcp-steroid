package com.jonnyzzz.mcpSteroid.report

import java.io.File
import java.time.Instant

/**
 * Pure end-to-end assembly: read a collected input dir, pair with/without runs, wrap into a [Report].
 * Kept separate from [main] so the whole pipeline is unit-testable without touching argv or the filesystem
 * output side.
 */
fun buildReport(inputDir: File, title: String, generatedAt: String): Report {
    val collected = InputReader.readAll(inputDir)
    return Report(
        title = title,
        generatedAt = generatedAt,
        comparisons = Aggregator.compare(collected.latest),
        allRuns = collected.latest,
        collectedBuilds = InputReader.readBuildMetas(inputDir),
        // generatedAt is the "now" of the recency weighting — the clock is threaded through, the
        // pure pipeline never reads the wall clock itself.
        histories = runHistories(collected.allBuilds, parseFinishDate(generatedAt)),
    )
}

private const val DEFAULT_TITLE = "MCP Steroid — test-experiments dashboard"

/**
 * CLI used by both the CI report build and a local run:
 *
 *   --input <dir>   directory of collected logs/summaries (default: current dir)
 *   --out <file>    output HTML file (default: <input>/index.html)
 *   --title <text>  dashboard title
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val inputDir = File(opts["input"] ?: ".")
    require(inputDir.isDirectory) { "--input must be an existing directory: $inputDir" }
    val outFile = File(opts["out"] ?: File(inputDir, "index.html").path)
    val title = opts["title"] ?: DEFAULT_TITLE

    val report = buildReport(inputDir, title, Instant.now().toString())
    outFile.absoluteFile.parentFile?.mkdirs()
    outFile.writeText(HtmlRenderer.render(report))

    val summaries = agentSummaries(report.comparisons)
    println("[report] input=${inputDir.absolutePath}")
    println("[report] runs=${report.allRuns.size} comparisons=${report.comparisons.size}")
    for (s in summaries) {
        println("[report]   ${s.agent}: ${s.helped} helped, ${s.hurt} hurt, ${s.neutral} neutral, ${s.incomplete} incomplete")
    }
    println("[report] wrote ${outFile.absolutePath}")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val value = args.getOrNull(i + 1)
            if (value != null && !value.startsWith("--")) {
                out[key] = value; i += 2; continue
            }
        }
        i++
    }
    return out
}
