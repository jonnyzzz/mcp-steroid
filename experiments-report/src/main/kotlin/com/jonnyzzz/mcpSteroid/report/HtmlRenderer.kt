package com.jonnyzzz.mcpSteroid.report

/**
 * Renders a [Report] into a single self-contained HTML document (inline CSS, no external assets) so it
 * can be dropped straight into a CI report tab or opened from disk after a local run.
 *
 * Layout:
 *  - per-agent summary cards (how many tasks MCP helped / hurt / left neutral)
 *  - the PRIMARY view: one table per agent, each scenario shown side-by-side WITH vs WITHOUT MCP
 *  - a secondary "Top problems" section mined across every run
 */
object HtmlRenderer {
    fun render(report: Report): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(report.title)).append("</title>\n")
        sb.append("<style>\n").append(CSS).append("\n</style>\n</head>\n<body>\n")

        sb.append("<header><h1>").append(esc(report.title)).append("</h1>")
        sb.append("<div class=\"meta\">generated ").append(esc(report.generatedAt))
            .append(" · ").append(report.comparisons.size).append(" task×agent comparisons · ")
            .append(report.allRuns.size).append(" runs</div>")
        sb.append("<div class=\"note\">All compared durations are <strong>agent execution time</strong> only — the ")
            .append("IDE preparation phase (container start, IDE download, project open &amp; indexing, pre-warm) ")
            .append("is measured separately and excluded from the agent comparison.</div></header>\n")

        renderSummary(sb, report)
        renderOverview(sb, report)
        renderComparisons(sb, report)
        renderProblems(sb, report)
        renderCoverage(sb, report)

        sb.append("<footer>This report is <strong>computed deterministically from</strong> the collected run data — ")
            .append("every verdict, number, and graph segment is derived by the report code (")
            .append("<code>:experiments-report</code>), <strong>not authored by an LLM/agent</strong>. The only free ")
            .append("text is each agent's own run <em>summary</em>, shown verbatim. Verdicts are a heuristic over the ")
            .append("objective build/test outcome — see the raw columns for nuance.</footer>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    // ── Per-agent summary ─────────────────────────────────────────────────────
    private fun renderSummary(sb: StringBuilder, report: Report) {
        val summaries = agentSummaries(report.comparisons)
        sb.append("<section><h2>Summary — with vs without MCP Steroid</h2>\n<div class=\"cards\">\n")
        for (s in summaries) {
            sb.append("<div class=\"card\"><div class=\"card-agent\">").append(esc(s.agent)).append("</div>")
            sb.append("<div class=\"card-row\"><span class=\"badge helped\">").append(s.helped).append(" helped</span>")
            sb.append("<span class=\"badge hurt\">").append(s.hurt).append(" hurt</span>")
            sb.append("<span class=\"badge neutral\">").append(s.neutral).append(" neutral</span>")
            if (s.incomplete > 0) sb.append("<span class=\"badge incomplete\">").append(s.incomplete).append(" incomplete</span>")
            sb.append("</div><div class=\"card-total\">").append(s.total).append(" tasks</div></div>\n")
        }
        sb.append("</div></section>\n")
    }

    // ── Overview graph: verdict mix per agent (inline SVG, computed from the data) ──
    private fun renderOverview(sb: StringBuilder, report: Report) {
        val summaries = agentSummaries(report.comparisons)
        if (summaries.isEmpty()) return
        sb.append("<section><h2>Overview</h2>\n")
        sb.append("<p class=\"meta\">Verdict mix per agent — every segment width is computed from the collected ")
            .append("runs (helped / hurt / neutral / incomplete). Hover a segment for its count.</p>\n")
        val barW = 360
        for (s in summaries) {
            val total = if (s.total > 0) s.total else 1
            sb.append("<div class=\"ov-row\"><span class=\"ov-label\">").append(esc(s.agent)).append("</span>")
            sb.append("<svg class=\"ov-bar\" width=\"$barW\" height=\"18\" role=\"img\" aria-label=\"")
                .append(esc(s.agent)).append(": ${s.helped} helped, ${s.hurt} hurt, ${s.neutral} neutral, ${s.incomplete} incomplete\">")
            var x = 0
            val segs = listOf("helped" to s.helped, "hurt" to s.hurt, "neutral" to s.neutral, "incomplete" to s.incomplete)
            for ((i, seg) in segs.withIndex()) {
                val (cls, count) = seg
                if (count == 0) continue
                // give the final non-empty segment the rounding remainder so the bar always fills exactly.
                val w = if (i == segs.indexOfLast { it.second > 0 }) barW - x else count * barW / total
                sb.append("<rect class=\"seg $cls\" x=\"$x\" y=\"0\" width=\"$w\" height=\"18\"><title>$cls: $count</title></rect>")
                x += w
            }
            sb.append("</svg>")
            sb.append("<span class=\"ov-counts\">")
                .append("<span class=\"good\">${s.helped}</span>/<span class=\"bad\">${s.hurt}</span>/")
                .append("${s.neutral}/${s.incomplete} <span class=\"subtle\">of ${s.total}</span></span></div>\n")
        }
        sb.append("</section>\n")
    }

    // ── Primary: per-agent with/without tables ──────────────────────────────────
    private fun renderComparisons(sb: StringBuilder, report: Report) {
        val byAgent = report.comparisons.groupBy { it.agent }.toSortedMap()
        for ((agent, comps) in byAgent) {
            sb.append("<section><h2>").append(esc(agent)).append(" — per task</h2>\n")
            sb.append("<table>\n<thead><tr>")
            sb.append("<th>task</th><th>verdict</th><th>with MCP</th><th>without MCP</th><th>Δ agent time</th><th>Δ cost</th>")
            sb.append("</tr></thead>\n<tbody>\n")
            for (c in comps) {
                sb.append("<tr>")
                sb.append("<td class=\"task\">").append(esc(c.scenario)).append("</td>")
                sb.append("<td>").append(verdictBadge(c.verdict)).append("</td>")
                sb.append("<td>").append(runCell(c.withMcp)).append("</td>")
                sb.append("<td>").append(runCell(c.without)).append("</td>")
                sb.append("<td class=\"num\">").append(durationDelta(c.durationDeltaMs)).append("</td>")
                sb.append("<td class=\"num\">").append(costDelta(c.costDeltaUsd)).append("</td>")
                sb.append("</tr>\n")
                val toolDiff = renderToolDiff(c.withMcp, c.without)
                if (toolDiff.isNotEmpty()) {
                    sb.append("<tr class=\"detail\"><td colspan=\"6\">").append(toolDiff).append("</td></tr>\n")
                }
                val summary = c.withMcp?.summary ?: c.without?.summary
                if (!summary.isNullOrBlank()) {
                    sb.append("<tr class=\"detail\"><td colspan=\"6\"><span class=\"detail-label\">summary:</span> ")
                        .append(esc(summary)).append("</td></tr>\n")
                }
            }
            sb.append("</tbody>\n</table>\n</section>\n")
        }
    }

    private fun runCell(r: AgentRun?): String {
        if (r == null) return "<span class=\"missing\">— no run —</span>"
        val bits = mutableListOf<String>()
        r.claimedFix?.let { bits += if (it) "<span class=\"ok\">✔ fix</span>" else "<span class=\"bad\">✗ no fix</span>" }
        r.buildSuccess?.let { bits += if (it) "<span class=\"ok\">build ✔</span>" else "<span class=\"bad\">build ✗</span>" }
        if (r.testsRun != null) bits += "tests ${r.testsRun - (r.testsFail ?: 0)}/${r.testsRun}"
        r.agentDurationMs?.let { bits += fmtDuration(it) + " agent" }
        r.costUsd?.let { bits += "$" + String.format("%.2f", it) }
        if (r.inputTokens != null || r.outputTokens != null) {
            bits += "${fmtInt(r.inputTokens)}→${fmtInt(r.outputTokens)} tokens"
        }
        r.numTurns?.let { bits += "$it turns" }
        val line1 = bits.joinToString(" · ")
        // model / version / token budget — straight from the agent output
        val id = mutableListOf<String>()
        r.model?.let { id += esc(it) }
        r.agentVersion?.let { id += "v" + esc(it) }
        r.contextWindow?.let { id += "ctx ${fmtInt(it)}" }
        r.maxOutputTokens?.let { id += "max out ${fmtInt(it)}" }
        val line2 = if (id.isEmpty()) "" else "<div class=\"subtle\">" + id.joinToString(" · ") + "</div>"
        return line1 + line2
    }

    /** Per-tool with-vs-without diff for one comparison, or empty when neither side reported tool calls. */
    private fun renderToolDiff(withMcp: AgentRun?, without: AgentRun?): String {
        val w = withMcp?.toolCalls ?: emptyMap()
        val o = without?.toolCalls ?: emptyMap()
        if (w.isEmpty() && o.isEmpty()) return ""
        val names = (w.keys + o.keys).toSortedSet()
        val parts = names.map { name ->
            val a = w[name] ?: 0
            val b = o[name] ?: 0
            val d = a - b
            val deltaClass = if (d == 0) "neutralTxt" else if (d < 0) "good" else "warn"
            val delta = if (d == 0) "" else " <span class=\"$deltaClass\">(${if (d > 0) "+" else ""}$d)</span>"
            "<span class=\"tool\">${esc(shortTool(name))} <b>$a</b>/<b>$b</b>$delta</span>"
        }
        return "<span class=\"detail-label\">tool calls (with / without):</span> " + parts.joinToString(" ")
    }

    private fun shortTool(name: String): String =
        if (name.startsWith("mcp__")) name.substringAfterLast("__") else name

    private fun fmtInt(n: Long?): String = if (n == null) "?" else String.format("%,d", n)

    // ── Secondary: top problems ─────────────────────────────────────────────────
    private fun renderProblems(sb: StringBuilder, report: Report) {
        sb.append("<section><h2>Top problems</h2>\n")
        val problems = topProblems(report.allRuns)
        if (problems.isEmpty()) {
            sb.append("<p class=\"empty\">No problems detected across collected runs.</p>\n</section>\n")
            return
        }
        sb.append("<table>\n<thead><tr><th>problem</th><th>count</th><th>where</th></tr></thead>\n<tbody>\n")
        for (p in problems) {
            sb.append("<tr><td>").append(esc(p.title)).append("</td>")
                .append("<td class=\"num\">").append(p.count).append("</td>")
                .append("<td class=\"where\">").append(esc(p.detail)).append("</td></tr>\n")
        }
        sb.append("</tbody>\n</table>\n</section>\n")
    }

    // ── Coverage: which collected builds produced run data ──────────────────────
    private fun renderCoverage(sb: StringBuilder, report: Report) {
        if (report.collectedBuilds.isEmpty()) return
        // Builds whose status is not SUCCESS — their run data may be partial or absent (e.g. the build
        // failed during IDE preparation, before any [ARENA] block was emitted).
        val gaps = report.collectedBuilds.filter { it.status != null && !it.status.equals("SUCCESS", true) }

        sb.append("<section><h2>Coverage</h2>\n")
        sb.append("<p class=\"meta\">").append(report.collectedBuilds.size)
            .append(" builds collected · ").append(report.allRuns.size).append(" runs parsed · ")
            .append(gaps.size).append(" build(s) not SUCCESS (data may be partial/absent)</p>\n")
        sb.append("<table>\n<thead><tr><th>build config</th><th>scenario</th><th>agent</th><th>status</th></tr></thead>\n<tbody>\n")
        for (m in report.collectedBuilds) {
            val ok = m.status.equals("SUCCESS", true)
            val statusCell = if (ok) "<span class=\"ok\">${esc(m.status)}</span>"
                else "<span class=\"bad\">${esc(m.status ?: "?")}</span>"
            sb.append("<tr><td class=\"where\">").append(esc(m.buildConfigId)).append("</td>")
                .append("<td>").append(esc(m.scenario)).append("</td>")
                .append("<td>").append(esc(m.agent)).append("</td>")
                .append("<td>").append(statusCell).append("</td></tr>\n")
        }
        sb.append("</tbody>\n</table>\n</section>\n")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────
    private fun verdictBadge(v: Verdict): String = when (v) {
        Verdict.MCP_HELPED -> "<span class=\"badge helped\">MCP helped</span>"
        Verdict.MCP_HURT -> "<span class=\"badge hurt\">MCP hurt</span>"
        Verdict.NEUTRAL -> "<span class=\"badge neutral\">neutral</span>"
        Verdict.INCOMPLETE -> "<span class=\"badge incomplete\">incomplete</span>"
    }

    private fun durationDelta(ms: Long?): String {
        if (ms == null) return "—"
        val s = ms / 1000
        return when {
            s < 0 -> "<span class=\"good\">−${fmtDuration(-ms)} faster</span>"
            s > 0 -> "<span class=\"warn\">+${fmtDuration(ms)} slower</span>"
            else -> "0s"
        }
    }

    private fun costDelta(usd: Double?): String {
        if (usd == null) return "—"
        return when {
            usd < 0 -> "<span class=\"good\">−$%.2f</span>".format(-usd)
            usd > 0 -> "<span class=\"warn\">+$%.2f</span>".format(usd)
            else -> "$0.00"
        }
    }

    private fun fmtDuration(ms: Long): String {
        val totalS = ms / 1000
        val m = totalS / 60
        val s = totalS % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val CSS = """
        :root { --bg:#0f1115; --panel:#171a21; --line:#272b35; --fg:#e6e8ec; --mut:#9aa3b2;
                --green:#2f9e44; --red:#e03131; --grey:#5c636e; --amber:#e8950c; }
        * { box-sizing:border-box; }
        body { margin:0; background:var(--bg); color:var(--fg);
               font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif; }
        header { padding:24px 28px; border-bottom:1px solid var(--line); }
        h1 { margin:0 0 4px; font-size:22px; }
        h2 { font-size:16px; margin:0 0 12px; }
        .meta { color:var(--mut); font-size:13px; }
        .note { margin-top:10px; color:var(--mut); font-size:12px; background:var(--panel);
                border:1px solid var(--line); border-left:3px solid var(--amber); border-radius:6px; padding:8px 12px; }
        section { padding:20px 28px; border-bottom:1px solid var(--line); }
        .cards { display:flex; flex-wrap:wrap; gap:12px; }
        .card { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:14px 16px; min-width:200px; }
        .card-agent { font-weight:600; font-size:15px; margin-bottom:8px; }
        .card-row { display:flex; gap:6px; flex-wrap:wrap; }
        .card-total { color:var(--mut); margin-top:8px; font-size:12px; }
        table { width:100%; border-collapse:collapse; background:var(--panel);
                border:1px solid var(--line); border-radius:10px; overflow:hidden; }
        th, td { text-align:left; padding:9px 12px; border-bottom:1px solid var(--line); vertical-align:top; }
        th { color:var(--mut); font-weight:600; font-size:12px; text-transform:uppercase; letter-spacing:.04em; }
        td.task { font-weight:600; }
        td.num, td.where { white-space:nowrap; }
        td.where { white-space:normal; color:var(--mut); font-size:13px; }
        tr.detail td { color:var(--mut); font-size:13px; background:#12151b; }
        .detail-label { color:var(--mut); text-transform:uppercase; font-size:11px; letter-spacing:.04em; }
        .badge { display:inline-block; padding:2px 8px; border-radius:999px; font-size:12px; font-weight:600; }
        .badge.helped { background:rgba(47,158,68,.18); color:#69db7c; }
        .badge.hurt { background:rgba(224,49,49,.18); color:#ff8787; }
        .badge.neutral { background:rgba(92,99,110,.25); color:#c1c7d0; }
        .badge.incomplete { background:rgba(232,149,12,.18); color:#ffd07b; }
        .ok { color:#69db7c; } .bad { color:#ff8787; }
        .good { color:#69db7c; } .warn { color:#ffd07b; } .neutralTxt { color:var(--mut); }
        .subtle { color:var(--mut); font-size:12px; margin-top:3px; }
        .tool { display:inline-block; background:#12151b; border:1px solid var(--line); border-radius:6px;
                padding:1px 7px; margin:2px 3px 0 0; font-size:12px; }
        .ov-row { display:flex; align-items:center; gap:10px; margin:5px 0; }
        .ov-label { width:90px; font-weight:600; }
        .ov-bar { border:1px solid var(--line); border-radius:3px; background:var(--panel); }
        .ov-counts { font-size:12px; color:var(--fg); }
        .seg.helped { fill:#2f9e44; } .seg.hurt { fill:#e03131; }
        .seg.neutral { fill:#5c636e; } .seg.incomplete { fill:#e8950c; }
        .missing { color:var(--grey); font-style:italic; }
        .empty { color:var(--mut); }
        footer { padding:16px 28px; color:var(--mut); font-size:12px; }
    """.trimIndent()
}

/** Per-agent roll-up of verdicts, sorted by agent name. */
fun agentSummaries(comparisons: List<Comparison>): List<AgentSummary> =
    comparisons.groupBy { it.agent }.toSortedMap().map { (agent, comps) ->
        AgentSummary(
            agent = agent,
            helped = comps.count { it.verdict == Verdict.MCP_HELPED },
            hurt = comps.count { it.verdict == Verdict.MCP_HURT },
            neutral = comps.count { it.verdict == Verdict.NEUTRAL },
            incomplete = comps.count { it.verdict == Verdict.INCOMPLETE },
        )
    }

/**
 * Mine problems across every run, ranked by frequency. Pure heuristics over the fields we already have;
 * deliberately conservative so the section stays signal, not noise.
 */
fun topProblems(runs: List<AgentRun>): List<Problem> {
    fun where(rs: List<AgentRun>) = rs.take(8).joinToString(", ") { "${it.scenario}/${it.agent}/${if (it.mode == McpMode.WITH) "mcp" else "none"}" }
    val out = mutableListOf<Problem>()

    runs.filter { it.claimedFix == false }.takeIf { it.isNotEmpty() }?.let {
        out += Problem("Agent did not claim a fix", it.size, where(it))
    }
    runs.filter { it.buildSuccess == false }.takeIf { it.isNotEmpty() }?.let {
        out += Problem("Build failed in sandbox", it.size, where(it))
    }
    runs.filter { (it.testsFail ?: 0) > 0 }.takeIf { it.isNotEmpty() }?.let {
        out += Problem("Tests failing", it.sumOf { r -> r.testsFail ?: 0 }, where(it))
    }
    runs.filter { it.exitCode != null && it.exitCode != 0 }.takeIf { it.isNotEmpty() }?.let {
        out += Problem("Non-zero agent exit code", it.size, where(it))
    }
    return out.sortedByDescending { it.count }
}
