---
title: "Archived: early DPAIA wall-clock table"
description: "The early DPAIA wall-clock comparison, kept for the record only — withdrawn as current evidence"
weight: 90
group: "Vision"
---

<div class="benchmark-footnote" style="border:1px solid rgba(254,40,87,0.35);border-radius:12px;padding:1rem 1.25rem;margin-bottom:1.5rem;">
<strong>Historical only — not current evidence.</strong> This early DPAIA wall-clock table was
removed from the homepage. Review (issue&nbsp;#251) found the measured runs did not meaningfully
exercise the intended product, so these numbers should <strong>not</strong> be read as a current
performance claim. It is kept here for the record only. For results backed by re-executable tests,
see <a href="/docs/experiment-findings/">Experiment Findings</a>.
</div>

The table below compared AI Agents with IDE-native semantic actions vs. file-only workflows on a
selection of DPAIA tasks. It is retained unedited for transparency.

<div class="benchmark-table-wrapper">
    <table class="benchmark-table">
        <thead>
            <tr>
                <th>Case</th>
                <th>Task</th>
                <th>With MCP</th>
                <th>Without MCP</th>
                <th>&Delta;</th>
            </tr>
        </thead>
        <tbody>
            <tr>
                <td class="benchmark-case">dpaia_jhipster_sample_app-3</td>
                <td>Rename ROLE_ADMIN across JHipster app (9 files)</td>
                <td>202s</td>
                <td>440s</td>
                <td class="benchmark-faster"><strong>&minus;54%</strong></td>
            </tr>
            <tr>
                <td class="benchmark-case">dpaia_empty_maven_springboot3-1</td>
                <td>JWT auth from scratch (5+ new files)</td>
                <td>288s</td>
                <td>396s</td>
                <td class="benchmark-faster"><strong>&minus;27%</strong></td>
            </tr>
            <tr>
                <td class="benchmark-case">dpaia_feature_service-25</td>
                <td>Parent-child JPA &amp; Flyway (10 files)</td>
                <td>382s</td>
                <td>523s</td>
                <td class="benchmark-faster"><strong>&minus;27%</strong></td>
            </tr>
            <tr>
                <td class="benchmark-case">dpaia_feature_service-125</td>
                <td>Multi-layer JPA+service+controller (15 files)</td>
                <td>788s</td>
                <td>1002s</td>
                <td class="benchmark-faster"><strong>&minus;21%</strong></td>
            </tr>
            <tr>
                <td class="benchmark-case">dpaia_spring_petclinic_rest-14</td>
                <td>Simple URL prefix replace (7 files)</td>
                <td>188s</td>
                <td>181s</td>
                <td>+4%</td>
            </tr>
            <tr>
                <td class="benchmark-case">dpaia_train_ticket-1</td>
                <td>Extend OrderRepository JPQL (4 files)</td>
                <td>727s</td>
                <td>633s</td>
                <td>+15%</td>
            </tr>
        </tbody>
    </table>
</div>
