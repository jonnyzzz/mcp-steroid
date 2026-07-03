# IntelliJ power features — A/B experiment candidates

> **AI generated** — research survey (2026-07) of IDE capabilities that would make strong
> with/without-MCP experiments, i.e. things an agent armed only with shell tools structurally
> cannot replicate. Follows the pattern of the existing experiments
> (`KeycloakRenameTest`, `KeycloakChangeSignatureTest`, `StructuralSearchYoutrackdbTest`):
> two identically-scored legs per agent, evidence-based markers, `[ARENA]` dashboard blocks
> via `SemanticRunRecorder`, correctness as a dashboard metric rather than a hard gate.

## How candidates were ranked

Score = **win-strength** (how badly the shell baseline should lose, and how visibly)
× **scoreability** (can a pure scorer in `SemanticTaskScoring.kt` verify the outcome from
machine-checkable markers + a pinned-revision ground truth) × **effort** (S/M/L, where the
existing container/prompt/scoring infra is the baseline — S means "new prompt + scorer only").

Win types: **correctness** (baseline produces wrong answers), **completeness** (baseline
misses items), **speed** (baseline takes far longer), **safety** (baseline breaks things it
should not touch).

## Ranked summary

| # | Candidate | Win type | Scoreability | Effort | Target repo |
|---|-----------|----------|--------------|--------|-------------|
| 1 | Type Migration (dataflow-propagated type change) | correctness + completeness + speed | High | M | Keycloak (pinned 26.6.4) |
| 2 | Spring Data derived-query & JPQL validation | correctness (compiles-fine bugs) | High | M | Broadleaf or spring-petclinic + patch |
| 3 | SSR *Replace* codemod with Count/absence filters | completeness + safety | High | S | youtrackdb (pinned) |
| 4 | Convert to Record — batch language-level migration | completeness + safety | High | M | Keycloak or youtrackdb |
| 5 | Extract Interface + "use interface where possible" | correctness + safety | High | M | Keycloak |
| 6 | Safe Delete — dead-feature removal sweep | safety | High | S–M | Keycloak |
| 7 | Cyclic package dependencies / DSM | completeness | Medium–High | M | youtrackdb / Keycloak |
| 8 | Locate Duplicates (rename-insensitive clone detection) | completeness | Medium | M | ThingsBoard / Keycloak |
| 9 | Dataflow to Here (backward slice) | correctness + completeness | Medium | M | Keycloak |
| 10 | Endpoints enumeration (Spring MVC + clients) | completeness | Medium–High | M | Broadleaf / ThingsBoard |
| 11 | Language-injection audit (regex/SQL in literals) | completeness | Medium | M | any Java repo + patch |
| 12 | UML / diagrams | — | Low | L | — |

The top five are elaborated first; each section carries: what the IDE does, why shell tools
structurally cannot, an experiment sketch, effort, and expected win type, with sources.

---

## 1. Type Migration — dataflow-propagated type change

**What the IDE does.** `Refactor | Type Migration` (Ctrl+Shift+F6) changes the declared type
of a field, variable, method return or parameter and then **propagates the change along
data-flow dependencies**: getters/setters, assignments, call arguments, dependent
declarations — across layers. Conflicts it cannot migrate automatically (e.g. `equals()`
becoming inapplicable to a primitive) are reported in a preview where individual nodes can be
excluded. Type-use annotations migrate together with the type. Programmatically this is
`com.intellij.refactoring.typeMigration.TypeMigrationProcessor` — a direct sibling of the
`ChangeSignatureProcessor` already exercised by `KeycloakChangeSignatureTest`.

- https://www.jetbrains.com/help/idea/type-migration.html
- https://blog.jetbrains.com/idea/2008/06/type-migration-refactoring/
- https://github.com/JetBrains-Research/data-driven-type-migration (IntelliTC — shows JetBrains
  Research treats this refactoring as hard enough to mine 250 repos for conversion rules)

**Why shell cannot.** The set of declarations to update is defined by *data flow*, not by
name: the same identifier text appears in hundreds of unrelated places, and the places that
DO need updating (a local that receives the field via a getter three calls away) share no
textual signature at all. sed over-matches and under-matches simultaneously; the only shell
fallback is compile-error-chasing, which converges slowly and misses semantic breakage that
still compiles (e.g. `Object`-typed sinks, autoboxing, `equals` misuse).

**Experiment sketch.** Keycloak pinned 26.6.4 (already deployed as `KeycloakProject`).
Task: migrate a widely-flowing field/parameter type — e.g. a `String` id or timeout field on
a core model class that flows through getters into services — to `long` (or `Duration`).
Pre-compute the ground truth once with the IDE at the pinned tag: the set of
`<file>:<declaration>` nodes the migration touches. Markers:
`TYPE_MIGRATED: yes`, `DECLARATIONS_UPDATED: <n>`, `DECLARATION_UPDATED: <FQN#member>`
(one per line), `BUILD_AFTER_CHANGE: SUCCESS|FAILURE`, `TOOL_EVIDENCE` on the MCP leg.
Scorer: exact-set match against ground truth + build green — same shape as
`scoreChangeSignature`. 80-min timeout, KeycloakRenameTest precedent (baseline is edit-heavy).

**Effort:** M (choose the seed symbol, derive ground truth, new scorer + prompts).
**Expected win:** correctness + completeness + speed — the strongest analog of the
change-signature win, with an even weaker textual signal for the baseline to grep for.

---

## 2. Spring Data derived-query & JPQL validation — bugs that compile fine

**What the IDE does.** IntelliJ Ultimate parses Spring Data **derived query method names**
(`findByLastnameOrderByUnknownDesc`) against the entity model and reports
"Cannot resolve property" via `SpringDataMethodInconsistencyInspection`; it likewise
validates JPQL/named queries in `@Query` strings against entities
(`JpaQueryApiInspection`, "Unresolved queries and query parameters"). These bugs pass
`javac` and every grep — they only explode at application startup or first query execution.

- https://www.jetbrains.com/help/inspectopedia/SpringDataMethodInconsistencyInspection.html
- https://www.jetbrains.com/help/inspectopedia/JpaQueryApiInspection.html
- https://blog.jetbrains.com/idea/2017/03/spring-data-improvements-in-intellij-idea-2017-1/
- https://www.jetbrains.com/guide/java/tutorials/getting-started-spring-data-jpa/creating-repository-class/

**Why shell cannot.** Validation requires (a) the Spring Data method-name grammar,
(b) the resolved entity model including inheritance, embeddables and nested-property paths,
and (c) JPQL semantic analysis. None of that is in the file being read; the compiler is
green either way. A shell agent can only eyeball names against entity fields — feasible for
one repository interface, hopeless project-wide, and it has no oracle for the tricky cases
(nested properties `findByAddressZipCode`, casing splits, `OrderBy` clauses).

**Experiment sketch.** Use `ProjectFromGitCommitAndPatch` (DpaiaArenaTest precedent) to seed
a pinned Spring project — `BroadleafCommerceProject` is already deployed; spring-petclinic
would be a lighter alternative — with N (~8–12) broken repository methods and @Query strings
across several modules: misspelled properties, missing property after `And`, wrong parameter
count, plus a few *decoys* that look wrong but are valid (nested property paths). Task:
"audit all Spring Data repositories; list every method whose query cannot be built".
Markers: `INVALID_QUERY: <FQCN#method> — <reason>` one per line, `INVALID_QUERY_COUNT: <n>`.
Scorer: precision/recall against the seeded set; decoys reported = precision penalty.
The with-MCP leg runs the inspection by name via `steroid_execute_code` (the
`KeycloakInspectionsTest` machinery already drives project-wide inspections).

**Effort:** M (patch authoring + decoy design; scoring is a simple set-match).
**Expected win:** correctness — the most dramatic category possible, since the baseline's
usual crutch ("does it build?") is useless by construction.

---

## 3. SSR Replace codemod — Count filters, absence matching, hierarchy filters

**What the IDE does.** Beyond the read-only `Optional.get()` audit we already run
(`StructuralSearchYoutrackdbTest`), Structural Search & Replace supports **Replace
templates** with per-variable modifiers:

- **Count** `[min,max]` — match any number of parameters/statements; crucially
  **count `[0,0]` matches the *absence* of an element** (e.g. "classes named `*DTO` whose
  fields lack annotation X") — grep fundamentally cannot match what is not there,
  scoped to a PSI context.
- **Type filter with "within type hierarchy"** — match expressions whose *resolved* type is
  a subtype of T.
- **Reference filters, Groovy Script filters** over PSI nodes.
- Replace options: reformat, **shorten fully-qualified names, add static imports** — the
  rewritten code is re-integrated idiomatically, not spliced as text.
- Templates can be promoted to project-wide custom inspections (`SSBasedInspection`).

- https://www.jetbrains.com/help/idea/structural-search-and-replace.html
- https://www.jetbrains.com/help/idea/search-templates.html (modifiers reference)
- https://www.jetbrains.com/help/idea/structural-search-and-replace-examples.html
- https://www.jetbrains.com/help/idea/tutorial-work-with-structural-search-and-replace.html
- https://gist.github.com/62mkv/d1cdbac42e225d157226b3f8337c84af (the count-[0,0] DTO-annotation recipe)
- https://habr.com/en/companies/krista/articles/511128/

**Why shell cannot.** Same argument as the existing SSR audit, amplified by *replace*: the
match set depends on resolved types and PSI structure (comments/strings/formatting
invariant), and the rewrite must preserve compilability (imports, FQN shortening). A sed
codemod on a type-hierarchy-constrained pattern is not expressible; an absence-pattern
(`[0,0]`) sweep even less so.

**Experiment sketch.** youtrackdb pinned revision (infra + skill articles already exist).
Task: a batch codemod, e.g. "every call `log.info($msg$ + $arg$)` (or a project-specific
anti-pattern with a type-hierarchy constraint) becomes the parameterized form", or the
absence variant: "add `@NotNull` to every public method returning `Optional`-typed values
that lacks it". Ground truth = match set derived once at the pinned revision. Markers:
`SITES_CHANGED: <n>`, `SITE: <file>:<line>` per line, `BUILD_AFTER_CHANGE`, plus git-diff
verification (scorer can additionally require the run to report untouched decoy sites).
Scorer: set match + build green + zero decoy edits.

**Effort:** S — the container, skill articles, prompt and scoring conventions for SSR all
exist; this adds a Replace recipe and a diff-based scorer.
**Expected win:** completeness + safety (decoys measure over-editing).

---

## 4. Convert to Record — batch Java 16 language-level migration

**What the IDE does.** The `ClassCanBeRecord` inspection ("Class can be record class",
under Java language level migration aids | Java 16) identifies classes eligible for record
conversion — the eligibility check is global: **no subclasses anywhere in the project**, all
instance fields final, no initializers/generic constructors/native methods — and the
quick-fix rewrites the class *and its accessor call sites* (`getX()` → `x()`), warning when
conversion would make members more accessible.

- https://www.jetbrains.com/help/inspectopedia/ClassCanBeRecord.html
- https://www.jetbrains.com/guide/java/tips/convert-to-record/
- https://youtrack.jetbrains.com/issue/IDEA-290504 (conversion-strategy UX details)

**Why shell cannot.** Eligibility requires whole-project type-hierarchy knowledge ("no
subclasses" — absence, again) and semantic checks (does anything rely on the synthesized
`equals`/`hashCode` differing? do accessors get more visible?). The rewrite must also chase
accessor renames across the project. A shell agent will either convert too little (only
obvious POJOs) or too much (classes with subclasses / reflective access), and hand-migrating
`getX()` call sites is the rename problem all over again.

**Experiment sketch.** A pinned Java-17+ module (Keycloak modules are 17; youtrackdb also
works if its language level allows records). Task: "convert every eligible class in modules
X,Y to records; do not convert ineligible ones; keep the build green." Ground truth: run the
inspection once at the pinned revision → the eligible set; pick modules that also contain
plausible-looking *ineligible* classes (subclassed elsewhere) as natural decoys. Markers:
`CONVERTED: <FQCN>` per line, `CONVERTED_COUNT`, `BUILD_AFTER_CHANGE`. Scorer: recall on
eligible set, penalty for converting ineligible classes, build green.

**Effort:** M (ground-truth derivation + module selection).
**Expected win:** completeness + safety; also a nice story — "language-level migration" is
exactly what teams ask agents to do at scale.

---

## 5. Extract Interface with "use interface where possible"

**What the IDE does.** `Refactor | Extract | Interface` creates an interface from selected
members and — with "Rename original class and use interface where possible" — **rewrites
declarations, parameters, returns and locals across the project to the new abstraction, but
only where type-correct**: constructor calls and usages of implementation-specific members
keep the concrete class. Conflict analysis runs before any change. The same family covers
Extract Superclass and Pull Members Up (visibility auto-raising, make-abstract).

- https://www.jetbrains.com/help/idea/extract-interface.html
- https://www.jetbrains.com/help/idea/extract-superclass.html
- https://www.jetbrains.com/help/idea/refactoring-source-code.html

**Why shell cannot.** "Where possible" is the whole feature: for every usage site the IDE
type-checks whether the narrowed type still satisfies all member accesses flowing from that
site. Textually the eligible and ineligible sites are identical (`Foo f = ...`). A baseline
either rewrites nothing beyond the declaration or breaks compilation at every
`new`/impl-specific site; and it cannot cheaply *prove* it found all rewrite-eligible sites.

**Experiment sketch.** Keycloak: pick a concrete class with many usages, a couple of
impl-specific members, and constructor call sites (ground truth derived once with the IDE:
set of rewritten vs. deliberately-untouched sites). Task: extract interface `X` with members
M1..Mk, use it everywhere possible. Markers: `INTERFACE_CREATED: <FQN>`,
`USAGE_REWRITTEN: <file>:<line>` per line, `BUILD_AFTER_CHANGE`. Scorer: rewritten-set
recall + zero rewrites at must-stay-concrete sites (safety axis) + build green.

**Effort:** M. **Expected win:** correctness + safety.

---

## 6. Safe Delete — dead-feature removal sweep

**What the IDE does.** `Refactor | Safe Delete` (Alt+Delete) searches usages *before*
deleting and refuses/flags deletion while live usages exist, including in comments, string
literals and non-code files; deleting a constructor-injected field removes the constructor
parameter too. Chained safe-deletes walk a dead-code island until it is fully removed.

- https://www.jetbrains.com/help/idea/safe-delete.html
- https://plugins.jetbrains.com/docs/intellij/safe-delete-refactoring.html

**Why shell cannot.** Usage search is the rename/find-usages problem again (reflection-free
resolution across modules, overloads, imports); grep over-approximates (same-name symbols)
and under-approximates (wildcard imports, non-code references). The baseline's only oracle
is the compiler, which does not flag unused-but-referenced-nowhere private helpers left
behind, nor stale references in resources.

**Experiment sketch.** Keycloak: pick a deprecated method/small feature whose transitive
private-helper island is known at the pinned tag (derive ground truth once). Task: "remove
`X` and everything that becomes dead because of it — nothing more." Markers:
`DELETED: <FQN#member>` per line, `BUILD_AFTER_CHANGE`. Scorer: deleted-set equals ground
truth (misses = completeness, extra deletions = safety violation), build green.
Overlaps conceptually with find-usages tests we have, but flips them into a *destructive*
task where the safety axis becomes measurable.

**Effort:** S–M. **Expected win:** safety (with a completeness component).

---

## 7. Cyclic package dependencies / Dependency Structure Matrix

**What the IDE does.** `Code | Analyze Code | Cyclic Dependencies` detects circular
relationships between packages; the DSM view (`Analyze | Dependency Matrix`, Ultimate,
bytecode-based — ~100k classes in ~2 min) visualizes dependency weights, marks cycles red,
and supports F2 jump-to-next-cycle and per-cell Find Usages for Dependencies.

- https://www.jetbrains.com/help/idea/dsm-analysis.html
- https://www.jetbrains.com/help/idea/dependencies-analysis.html
- https://blog.jetbrains.com/idea/2020/01/dsm-prepare-your-application-for-modularity/
- https://maritvandijk.com/explore-project-structure-with-dependency-matrix/

**Why shell cannot.** Import-header grepping misses same-package usage, FQN references
without imports, constants inlining, and gives no edge *weights*; computing the actual
class-graph SCCs from source requires resolution. The bytecode route (`jdeps`) exists but
demands a full build plus correct classpath assembly per module — doable in principle,
expensive and error-prone in practice, which makes this a *speed + completeness* rather than
an impossibility win.

**Experiment sketch.** youtrackdb or Keycloak at pinned rev. Task: "list every package-level
dependency cycle in modules X,Y" — ground truth derived once. Markers: `CYCLE: <pkgA> ->
<pkgB> -> ... -> <pkgA>` normalized (sorted rotation), `CYCLE_COUNT`. Scorer: set-match on
normalized cycles. Caveat: DSM needs compiled classes — the container must build first
(the Maven-import infra already does this for other tests).

**Effort:** M. **Expected win:** completeness + speed. Ranked below the top five because
`jdeps` gives the baseline a fighting chance, which weakens (but also makes more honest)
the comparison.

---

## 8. Locate Duplicates — rename-insensitive clone detection

**What the IDE does.** `Code | Analyze Code | Locate Duplicates` plus the on-the-fly
"Duplicated code fragment" inspection find repeated code with **anonymization**: constructs
that differ only in identifier names or literal values still count as duplicates (Type-2
clones). Results come with side-by-side diffs; `Refactor | Find and Replace Code Duplicates`
can then deduplicate against an extracted method.

- https://www.jetbrains.com/help/idea/analyzing-duplicates.html
- https://www.jetbrains.com/help/inspectopedia/DuplicatedCode.html
- https://www.jetbrains.com/help/idea/find-and-replace-code-duplicates.html

**Why shell cannot.** Textual dedup tools catch identical text; PSI anonymization catches
clones after variables were renamed and literals changed — no fixed-string or regex
formulation exists. The follow-up ("replace duplicates with a call to the extracted
method") also needs parameter inference over the varying parts.

**Experiment sketch.** ThingsBoard or Keycloak, pinned. Two-phase task: (1) report all
duplicate groups ≥ N units in a module — scored against IDE-derived ground truth (require a
recall floor rather than exact match, since near-threshold groups are noisy); (2) extract
one designated group into a method and replace all its occurrences — scored by build green +
occurrence count of the new call. Markers: `DUP_GROUP: <fileA>:<lineA> <fileB>:<lineB> ...`,
`EXTRACTED_METHOD: <FQN#name>`, `CALLS_TO_EXTRACTED: <n>`, `BUILD_AFTER_CHANGE`.

**Effort:** M. **Expected win:** completeness. Scoreability medium: duplicate-group
boundaries are threshold-sensitive, hence the recall-floor design.

---

## 9. Dataflow to Here — backward program slice

**What the IDE does.** `Code | Analyze Code | Data Flow to Here / from Here` computes an
interprocedural slice: all producers that can flow into a parameter/variable (with
group-by-nullness), or all consumers downstream — without running the program.

- https://www.jetbrains.com/help/idea/analyzing-data-flow.html
- https://www.jetbrains.com/guide/java/tips/data-flow-analysis/
- https://medium.com/javarevisited/how-to-find-the-source-of-nullable-data-f775c963c00e

**Why shell cannot.** Interprocedural value flow across call chains, fields and collections
is a static-analysis problem; grep finds *textual* mentions, not *value provenance*. The
classic task — "which call paths can pass null into parameter p?" — has no textual
formulation.

**Experiment sketch.** Keycloak: choose a method parameter with a known, non-trivial
producer set at the pinned tag (derive ground truth once via the IDE; export-to-text-file
support makes this reproducible). Task: "list every source location whose value can reach
parameter `p` of `M`, and state whether null can reach it." Markers:
`FLOW_SOURCE: <file>:<line>` per line, `NULL_REACHABLE: yes|no`. Scorer: recall on the
ground-truth source set + correct nullness verdict.

**Effort:** M. **Expected win:** correctness + completeness. Ranked mid-table because
ground truth is derived from the same engine being tested (acceptable — we already do this
for hierarchy tests — but it weakens the "objective" story slightly), and deep slices can
be large/noisy.

---

## 10. Endpoints view — full HTTP surface enumeration

**What the IDE does.** The Endpoints tool window aggregates every server and client HTTP/
WebSocket endpoint across frameworks (Spring MVC/WebFlux, Feign, JAX-RS, gRPC, Retrofit…),
composing class-level `@RequestMapping` prefixes with method mappings, resolving inherited
controllers and meta-annotations, marking deprecated URLs, and generating OpenAPI drafts and
runnable HTTP-client requests per endpoint.

- https://www.jetbrains.com/help/idea/endpoints-tool-window.html
- https://blog.jetbrains.com/idea/2020/10/intellij-idea-2020-3-eap6/
- https://www.jetbrains.com/help/idea/spring-boot.html

**Why shell cannot.** Grep for `@GetMapping` misses: path composition with class-level
prefixes and inherited mappings, custom meta-annotations (`@interface ApiV2Get`), Kotlin/
Java mixes, property-placeholder segments (`${api.prefix}`), conditional beans. Producing
the *actual* URL table requires the Spring model.

**Experiment sketch.** Broadleaf (deployed) or ThingsBoard (deployed, heavy Spring usage).
Task: "enumerate all HTTP endpoints of module X as `METHOD /full/path ->
Controller#method`". Ground truth once at pinned rev via the Endpoints model
(`steroid_execute_code`). Markers: `ENDPOINT: <METHOD> <path> <FQCN#method>` per line.
Scorer: precision/recall on the normalized endpoint set — very clean set-match scoring.

**Effort:** M. **Expected win:** completeness. Ranked here because on disciplined codebases
plain annotation-grepping gets embarrassingly close; pick a module with meta-annotations and
inheritance to keep the gap honest.

---

## 11. Language-injection audit — regex/SQL inside string literals

**What the IDE does.** IntelliLang injects languages into string literals by context rules
(e.g. the argument of `String.matches()` / `Pattern.compile()` is RegExp; JDBC/JPA call
arguments are SQL) or `@Language` annotations, then runs full syntax/semantic validation
inside the fragment — including fragments concatenated from several strings.

- https://www.jetbrains.com/help/idea/using-language-injections.html
- https://blog.jetbrains.com/idea/2020/06/language-injections-in-intellij-idea/
- https://plugins.jetbrains.com/docs/intellij/language-injection.html

**Why shell (mostly) cannot.** The *discovery* step is IDE-owned: which literals are regex/
SQL is determined by resolved call context and annotations, not by the string's shape; and
concatenated fragments must be assembled semantically. However, once literals are found, a
shell agent *can* validate a regex with an external engine — so the defensible gap is
discovery + concatenation + SQL-dialect awareness, not validation per se.

**Experiment sketch.** Any deployed Java repo + `ProjectFromGitCommitAndPatch` seeding ~10
broken injected fragments (invalid regex passed through a helper, SQL typo in a concatenated
query) plus decoys (invalid-regex-looking strings in non-regex contexts, which must NOT be
reported). Markers: `BAD_FRAGMENT: <file>:<line> — <reason>`. Scorer: precision/recall vs
the seeded set; the decoys are the point — they specifically punish context-free grepping.

**Effort:** M. **Expected win:** completeness with a precision twist. Ranked low-mid because
the baseline has partial workarounds.

---

## 12. UML / diagrams — surveyed, not recommended

Class diagrams, module-dependency diagrams and graph-centrality analysis
(https://www.jetbrains.com/help/idea/class-diagram.html) are genuinely IDE-only, but the
output is visual/exploratory; any textual marker we could score ("list parents/implementors
of X") collapses into the type-hierarchy experiments we already run
(`KeycloakTypeHierarchyTest`). No objective, non-redundant scoring story → not recommended.

Similarly surveyed and folded in rather than listed separately:
- **Pull Members Up / Push Down / Move Members** — same win mechanism as #5; run them as
  variants of the Extract Interface scenario rather than standalone tests.
- **JPA console** (https://www.jetbrains.com/help/idea/using-jpa-console.html) — running
  JPQL against a live persistence unit needs a database in the container; the *validation*
  half is already covered by #2 without that cost.
- **Spring bean wiring / @Qualifier / @Profile resolution**
  (https://blog.jetbrains.com/idea/2018/09/spring-kotlin-gutters-and-navigation/,
  https://blog.jetbrains.com/idea/2025/11/intellij-idea-2025-3-spring-7/) — strong
  IDE-only capability (which bean actually wires into an injection point given qualifiers,
  primaries and active profiles), but crisp ground truth requires careful seeding; treat as
  a follow-up to #2 on the same Spring target once that infra exists: marker shape
  `WIRED: <injectionPoint> <- <beanFQN>` scores cleanly.

---

## Cross-cutting design notes

- **Ground truth at a pinned revision** is the backbone of every design above — same as
  `REQUIRED_OVERRIDES` in `KeycloakChangeSignatureTest`. Derive once with the IDE, freeze in
  the test source with a comment explaining the derivation.
- **Decoys are the precision axis.** The seeded-defect designs (#2, #11) and
  must-not-touch sites (#3, #4, #5, #6) let scorers punish over-reporting/over-editing —
  this is what separates "ran the right analysis" from "grepped and guessed", and it guards
  against the historical hallucinated-win failure mode.
- **Destructive tasks need `BUILD_AFTER_CHANGE`** exactly as change-signature does; read-only
  audits (#2, #7, #9, #10, #11) don't, which makes them cheaper (50-min timeout class).
- **Effort-S first**: #3 (SSR Replace) reuses the entire existing SSR stack and is the
  natural next experiment; #1 (Type Migration) is the highest-impact M.
- TC config naming for the top candidates would follow the existing convention:
  `IntegrationTests_<Scenario>_{Claude,Codex}` with scenario ids like
  `KeycloakTypeMigration`, `SpringDataQueryAudit`, `SsrReplaceYoutrackdb`,
  `KeycloakConvertToRecord`, `KeycloakExtractInterface`.
