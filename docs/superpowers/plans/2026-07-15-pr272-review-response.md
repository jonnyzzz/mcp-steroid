# PR #272 (cli-hardening) Review Response — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the 18 review comments on PR #272 plus issue #266 (optional `--project_name`) by de-duplicating the devrig MCP-as-CLI layer against the existing tool machinery, cleaning up presentation, and inferring the project from the cwd — without regressing the frozen `--json`/exit-code contract.

**Architecture:** The devrig CLI exposes each `steroid_*` MCP tool as a subcommand. Today each command re-declares the tool's parameters (clikt options), re-builds the tool's `*Params`, and hand-serializes the result. This plan routes tool calls through the shared `ToolSpec.call(context)` path (the same path `devrig mcp` uses), replaces the hand-built JSON envelope with native `kotlinx.serialization`, introduces a `Presentation` abstraction to replace a threaded `json: Boolean`, and adds a cwd→project resolver over the routing snapshot. The full "generate CLI flags/help from the tool schema" redesign is explicitly a follow-up PR.

**Tech Stack:** Kotlin 2.3.20, kotlinx.serialization, clikt, JUnit 5 (jupiter), Gradle 9.5.1. Module `:npx-kt` (the devrig CLI) and `:mcp-steroid-server` (shared tool handlers/schema).

## Global Constraints

Copied verbatim from `CLAUDE.md` / `ij-plugin/CLAUDE.md`. Every task's requirements implicitly include these.

- Bytecode targets Java 21 (class-file v65); toolchain JDK 25. Do not raise the target.
- Banned: `internal` visibility modifier — use plain public (no modifier).
- Banned: `runCatching{}.onFailure{}` — use `try { } catch (e: Exception) { }`.
- Banned: empty `catch` / `catch (_: Exception) {}` — every catch must rethrow, log via `System.err.println` / `logger.error`, or both.
- Banned: returning `(value, errorFlag)` pairs from a fallible call — return the value or throw / return null.
- Banned: `@Suppress("DEPRECATION")` — find the non-deprecated replacement.
- Banned: hardcoded `mcp-steroid://…` URI literals in production Kotlin — use the generated `XxxPromptArticle().uri` (enforced by `NoHardcodedMcpSteroidUriUsageTest`).
- Prefer JSON libraries for JSON parsing/manipulation; only static final JSON constants may be hand-written.
- Tests must show reality. Never remove, disable, or weaken a failing test; fix the underlying issue. **Exception in this plan:** where a reviewer-requested change deliberately alters an observable contract (image rendering), the test that pinned the OLD contract is *rewritten* to pin the NEW contract — that is a contract change, not a weakening.
- Atomic commits; descriptive messages (what + why). Never mention AI or add AI co-authors.
- Test scoping: never `./gradlew test` at repo root. Use `./gradlew :npx-kt:test` and `./gradlew :mcp-steroid-server:test`; single test via `--tests '<pattern>'`.
- Keep the frozen contract green throughout: `McpAsCliContractTest`, `CliErrorEnvelopeTest`, `McpAsCliParseTest`, `ExecuteCodeCommandTest`, `ScreenshotAndOpenProjectCommandTest`, `FeedbackAndInputCommandTest`, `FetchResourceCommandTest`, `ListCommandsTest`, `ExecuteCodeHelpTopicTest`.
- `timeout`/`gtimeout` are unavailable on this Mac; use the Bash tool's own timeout or Gradle timeouts.
- Do not push or merge. Commit locally only. The branch is `cli-hardening`.

## File Structure

Module `:npx-kt` — `src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/`
- `CliToolSupport.kt` — MODIFY. Envelope + render helpers. Introduces `Presentation`; native serialization replaces `contentDataJson()`; console image → temp file.
- `ToolBackedCommands.kt` — MODIFY. Core reuse: build `arguments: JsonObject` → call `ToolSpec.call()`; project_name inference hook.
- `FetchResourceCommand.kt` — MODIFY. Use `Article` return type; use `promptsContextFromRoute`.
- `HelpCommand.kt` — MODIFY. Document `--code-file=-` (stdin); note optional `--project_name`.
- `Cli.kt` — MODIFY. `--project_name` optional for project-scoped commands; help text.
- `server/DevrigPromptsContextHandler.kt` — MODIFY. Add `promptsContextFromRoute(route)`; dedupe.
- `server/DevrigProjectRoutingService.kt` — MODIFY. Add `resolveProjectFromCwd(cwd): ProjectRoute` (or a result type) for #266.
- `server/CwdProjectResolver.kt` — CREATE (if the resolver is cleaner standalone). Longest-prefix cwd→route.

Module `:mcp-steroid-server` — `src/main/kotlin/com/jonnyzzz/mcpSteroid/server/`
- `FetchResourceToolHandler.kt` — MODIFY. `resolveResourcePayload` / `canonicalResourceEntryPoints` return article objects.

Tests (`:npx-kt` `src/test/kotlin/...devrig/`, `:mcp-steroid-server` `src/test/kotlin/...server/`)
- `CliToolSupportTest.kt` — MODIFY (image contract rewrite + presentation).
- `FetchResourceCommandTest.kt` — MODIFY (article return types).
- `ToolBackedCommands` glue tests (`CliGlueTestSupport.kt`, `ExecuteCodeCommandTest.kt`, etc.) — kept green; extended for #266.
- `CwdProjectResolverTest.kt` — CREATE (#266 resolver matrix).

> **Note on ordering:** the plan's task order refines the spec's A/B/C bucket order because the rendering tasks (image, envelope, presentation) are interdependent — they touch the same `CliToolSupport.kt` render path and must land in dependency order.

---

## Task 1: Fix the `!!` in `resolveCodeArg` (comment C16)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/ToolBackedCommands.kt:76-91`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/ExecuteCodeCommandTest.kt` (existing, kept green)

**Interfaces:**
- Consumes: `CodeArgException(message: String, val exit: Int)`, `CliExit.USAGE`.
- Produces: no signature change to `resolveCodeArg(inline: String?, file: String?): String`.

This is a code-smell fix, not a behavior change: `Cli.kt:471` already rejects both-blank at parse time, so the `file!!` branch is unreachable in practice. Replace the `!!` with an explicit guard that fails cleanly if ever reached.

- [ ] **Step 1: Run the existing execute_code tests to confirm the baseline is green**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeCommandTest*'`
Expected: PASS.

- [ ] **Step 2: Replace the `!!` with a guard**

In `resolveCodeArg`, change:

```kotlin
    val path = try {
        Path.of(file!!)
    } catch (e: InvalidPathException) {
```

to:

```kotlin
    val resolvedFile = file
        ?: throw CodeArgException("provide --code or --code-file", CliExit.USAGE)
    val path = try {
        Path.of(resolvedFile)
    } catch (e: InvalidPathException) {
```

and update the two later uses of `file` in this function (`Path.of`, and the `InvalidPathException`/`isRegularFile`/`readString` messages) to use `resolvedFile`.

- [ ] **Step 3: Run the tests to confirm still green**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeCommandTest*' --tests '*McpAsCliContractTest*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/ToolBackedCommands.kt
git commit -m "refactor(devrig): replace !! in resolveCodeArg with an explicit usage guard"
```

---

## Task 2: `Json { }` style + trim over-detailed comments (comment C5)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupport.kt:135`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupportTest.kt` (kept green)

**Interfaces:**
- Produces: `CLI_ENVELOPE_JSON` unchanged in behavior (`prettyPrint`, `encodeDefaults`, `explicitNulls=false`).

- [ ] **Step 1: Reformat the `Json { }` block over newlines, no `;`**

Change:

```kotlin
val CLI_ENVELOPE_JSON: Json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
```

to:

```kotlin
val CLI_ENVELOPE_JSON: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}
```

- [ ] **Step 2: Trim the over-detailed comments**

Shorten the multi-paragraph KDoc on `CLI_ENVELOPE_JSON` and on `renderScreenshotSaved`/`toEnvelopeJson` to one or two lines each (keep the one-line "what + why"; drop the prose the reviewer flagged as too detailed). Do not remove the copyright header.

- [ ] **Step 3: Run tests**

Run: `./gradlew :npx-kt:test --tests '*CliToolSupportTest*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupport.kt
git commit -m "style(devrig): newline the CLI envelope Json block; trim over-detailed comments"
```

---

## Task 3: Document `--code-file=-` stdin in help (comment C17)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/HelpCommand.kt:26-62` (the `printExecuteCodeHelp` block) and `:119-128` (global banner `execute_code` line)
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/ExecuteCodeHelpTopicTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: help text mentions `--code-file=-` reads the script from stdin.

`readBytes()` on `mcpStdin` (a `java.io.InputStream`) reads to EOF — no partial-read risk for a slow producer; it blocks until the stream closes. This task documents that affordance (it was implemented but undocumented).

- [ ] **Step 1: Add a failing assertion to the help topic test**

In `ExecuteCodeHelpTopicTest.kt`, add:

```kotlin
    @Test
    fun `execute_code help documents stdin via code-file dash`() {
        val out = ByteArrayOutputStream()
        printExecuteCodeHelp(PrintStream(out))
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.contains("--code-file=-") || text.contains("\"-\""),
            "expected stdin affordance documented, got:\n$text")
        assertTrue(text.contains("stdin"), text)
    }
```

Match the existing imports/style in that file (add `ByteArrayOutputStream`, `PrintStream`, `assertTrue` if missing).

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeHelpTopicTest*'`
Expected: FAIL (the current help lists `--code-file=-` only in the flags block; confirm the assertion actually fails, otherwise the doc already covers it — if so, drop this task).

- [ ] **Step 3: Update the help text**

In `printExecuteCodeHelp`, under `Required:` for `--code-file`, change the line to:

```
          --code-file      path to a .kts file; pass "-" to read the script from stdin (blocks until EOF)
```

and in the global banner (`printHelp`), extend the `execute_code` description similarly.

- [ ] **Step 4: Run tests**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeHelpTopicTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/HelpCommand.kt npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/ExecuteCodeHelpTopicTest.kt
git commit -m "docs(devrig): document --code-file=- stdin affordance in execute_code help"
```

---

## Task 4: Native serialization for the `--json` envelope content (comments C6 + C7)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupport.kt:205-249` (`toEnvelopeJson`, `contentDataJson`, `decodedByteCount`)
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupportTest.kt:45-59`

**Interfaces:**
- Consumes: `ContentItem` (sealed, `@SerialName` "text"/"image"/"resource"), `McpJson` (`classDiscriminator = "type"`), `ToolCallResult`.
- Produces: `ToolCallResult.contentDataJson(): JsonObject` still exists, but `content` array is built by serializing the `List<ContentItem>` with `McpJson`. Image objects now carry `data` (base64) as-is instead of a `bytes` count. Resource objects serialize to their native `{type:"resource","resource":{...}}` shape.

Background: `McpJson` uses `classDiscriminator = "type"`, and `ContentItem.Text` is `@SerialName("text")`, so serializing content natively yields exactly `{"type":"text","text":"…"}` for text (unchanged) and `{"type":"image","data":"…","mimeType":"…"}` for images (the "keep data as-is" the reviewer asked for). This removes the second hand-written copy of the serialization logic.

- [ ] **Step 1: Rewrite the image envelope test to pin the NEW contract**

Replace `CliToolSupportTest`'s `image renders a byte-count placeholder and the envelope reports bytes` test's **envelope** assertions (lines 55-58) with:

```kotlin
        val obj = Json.parseToJsonElement(result.toEnvelopeJson("shot")).jsonObject
        val item = obj["data"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject
        assertEquals("image", item["type"]!!.jsonPrimitive.content)
        // C7: image data is carried as-is (base64), not summarized to a byte count.
        assertEquals(b64, item["data"]!!.jsonPrimitive.content)
        assertEquals("image/png", item["mimeType"]!!.jsonPrimitive.content)
```

(The console `[image: …]` assertion on line 53 is handled in Task 6 — leave it for now; this task can temporarily keep the console line asserting the old placeholder. If that makes the test internally inconsistent, split the test into `envelope` and `console` cases here and finish the console half in Task 6.)

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :npx-kt:test --tests '*CliToolSupportTest*'`
Expected: FAIL (current envelope emits `bytes`, not `data`).

- [ ] **Step 3: Replace `contentDataJson()` with native serialization**

Rewrite `contentDataJson()` to serialize the content list via `McpJson`:

```kotlin
fun ToolCallResult.contentDataJson(): JsonObject = buildJsonObject {
    put("content", McpJson.encodeToJsonElement(
        ListSerializer(ContentItem.serializer()), content
    ))
}
```

Add imports: `com.jonnyzzz.mcpSteroid.mcp.McpJson`, `kotlinx.serialization.builtins.ListSerializer`, `kotlinx.serialization.json.put` (JsonElement overload). Delete the now-unused `decodedByteCount()` helper and the `java.util.Base64` import if no longer referenced elsewhere in the file (the console renderer in Task 6 will re-introduce its own decode).

- [ ] **Step 4: Run the full CliToolSupport + contract tests**

Run: `./gradlew :npx-kt:test --tests '*CliToolSupportTest*' --tests '*McpAsCliContractTest*' --tests '*CliErrorEnvelopeTest*' --tests '*ScreenshotAndOpenProjectCommandTest*'`
Expected: PASS. If a contract test pinned the old `bytes` image shape or the old flattened resource shape, update it to the native shape (this is the intended contract change; record it in the commit message).

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupport.kt npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupportTest.kt
git commit -m "refactor(devrig): serialize --json envelope content natively; keep image data as-is (C6,C7)"
```

---

## Task 5: `Presentation` abstraction replacing threaded `json: Boolean` (comment C3)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupport.kt` (introduce `Presentation`)
- Modify call sites: `FetchResourceCommand.kt:48`, `ToolBackedCommands.kt` (all `renderTo`/`renderCliError` calls), `ListProjectsCommand.kt`, `ListWindowsCommand.kt`, `Main.kt:149`, `Cli.kt:761`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupportTest.kt`

**Interfaces:**
- Produces: a sealed `Presentation` with two implementations and a uniform rendering surface, e.g.:

```kotlin
sealed interface Presentation {
    fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream): Int
    fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream): Int
    class Json : Presentation
    class Console(private val imageDir: () -> Path) : Presentation
}
fun presentationFor(json: Boolean, imageDir: () -> Path): Presentation
```

The exact method set is discovered while migrating call sites; the invariant is **no `if (json)` left in the shared render code** — each branch lives in its `Presentation` implementation. `Console` takes an `imageDir` provider so Task 6 (console image → file) has a home.

- [ ] **Step 1: Baseline green**

Run: `./gradlew :npx-kt:test`
Expected: PASS.

- [ ] **Step 2: Add a test that both presentations render the same result differently**

In `CliToolSupportTest.kt`:

```kotlin
    @Test
    fun `json presentation emits one envelope, console emits plain text`() {
        val result = ToolCallResult(content = listOf(ContentItem.Text("hi")))
        val (jo, je) = buffers()
        Presentation.Json().render(result, "demo", PrintStream(jo), PrintStream(je))
        val (co, ce) = buffers()
        Presentation.Console { java.nio.file.Files.createTempDirectory("t") }
            .render(result, "demo", PrintStream(co), PrintStream(ce))

        assertTrue(jo.text().trim().startsWith("{"), jo.text())
        assertEquals("hi", co.text().trim())
    }
```

- [ ] **Step 3: Run it to see it fail (types don't exist yet)**

Run: `./gradlew :npx-kt:test --tests '*CliToolSupportTest*'`
Expected: FAIL to compile / unresolved `Presentation`.

- [ ] **Step 4: Introduce `Presentation` and move the branch bodies into it**

Extract the current `if (json) …` bodies of `renderTo` and `renderCliError` into `Presentation.Json` / `Presentation.Console`. Keep `ToolCallResult.renderTo(command, json, out, err)` and `renderCliError(...)` as thin shims that delegate to `presentationFor(json, imageDir)` so existing call sites keep compiling; migrate call sites incrementally in Step 5. The `Json` impl uses `toEnvelopeJson` / `cliEnvelopeJson`; the `Console` impl does the stdout/stderr routing and the per-`ContentItem` `when`.

- [ ] **Step 5: Migrate call sites off the boolean where natural**

For each file in the call-site list, pass/construct the `Presentation` once per command instead of threading `json`. Where a command already has `command.json`, build `presentationFor(command.json, services.homePaths::screenshotTmpDir)` (add that provider in Task 6; for now `{ homePaths.home }`). Keep behavior identical.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew :npx-kt:test`
Expected: PASS (all ~1393 contract tests included).

- [ ] **Step 7: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/
git commit -m "refactor(devrig): replace threaded json:Boolean with a Presentation abstraction (C3)"
```

---

## Task 6: Console image → temp file under `~/.mcp-steroid/` (comment C4)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupport.kt` (`Presentation.Console` image branch)
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/HomePaths.kt` (add a tmp dir accessor)
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupportTest.kt`

**Interfaces:**
- Consumes: `HomePaths` (resolves `~/.mcp-steroid`).
- Produces: `HomePaths.screenshotTmpDir(): Path` (creates `~/.mcp-steroid/tmp` if missing); `Presentation.Console` writes decoded PNG bytes to `<tmpDir>/image-<n>.<ext>` and prints the absolute path.

- [ ] **Step 1: Rewrite the console image test to pin the file-path contract**

Replace the console assertion (old line 53) with:

```kotlin
    @Test
    fun `console image is written to a temp file and the path is printed`() {
        val raw = ByteArray(9) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(raw)
        val result = ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png")))
        val tmp = java.nio.file.Files.createTempDirectory("shot")
        val (out, err) = buffers()

        Presentation.Console { tmp }.render(result, "shot", PrintStream(out), PrintStream(err))

        val printed = out.text().trim()
        val path = java.nio.file.Path.of(printed.substringAfterLast(' ').ifBlank { printed })
        assertTrue(java.nio.file.Files.exists(path), "expected a written file, printed: $printed")
        assertEquals(9, java.nio.file.Files.size(path).toInt())
        assertEquals("image/png", result.content.filterIsInstance<ContentItem.Image>().first().mimeType)
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :npx-kt:test --tests '*CliToolSupportTest*'`
Expected: FAIL (console still prints the `[image: …]` placeholder).

- [ ] **Step 3: Add `HomePaths.screenshotTmpDir()`**

In `HomePaths.kt`:

```kotlin
    fun screenshotTmpDir(): Path {
        val dir = home.resolve("tmp")
        Files.createDirectories(dir)
        return dir
    }
```

(Match the file's existing `home: Path` field and imports.)

- [ ] **Step 4: Write the image in `Presentation.Console`**

In the `Console` image branch, decode the base64 and write it:

```kotlin
is ContentItem.Image -> {
    val bytes = try {
        Base64.getDecoder().decode(item.data)
    } catch (e: IllegalArgumentException) {
        System.err.println("devrig: image payload was not valid base64 (${e.message})")
        out.println("[image: ${item.mimeType}, undecodable]")
        return@forEach   // or the equivalent control flow for your loop shape
    }
    val ext = item.mimeType.substringAfterLast('/', "png")
    val file = imageDir().resolve("image-${index}.$ext")
    Files.write(file, bytes)
    out.println("Saved image: ${file.toAbsolutePath()}")
}
```

Add imports `java.nio.file.Files`, `java.util.Base64`, `java.nio.file.Path`.

- [ ] **Step 5: Run tests**

Run: `./gradlew :npx-kt:test --tests '*CliToolSupportTest*' --tests '*ScreenshotAndOpenProjectCommandTest*'`
Expected: PASS. Reconcile any `take_screenshot` contract test that asserted the old placeholder.

- [ ] **Step 6: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupport.kt npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/HomePaths.kt npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/CliToolSupportTest.kt
git commit -m "feat(devrig): console renders images to a ~/.mcp-steroid/tmp file and prints the path (C4)"
```

---

## Task 7: `resolveResourcePayload` / `canonicalResourceEntryPoints` return article objects (comments C1 + C2)

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/FetchResourceToolHandler.kt:64-110`
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/FetchResourceCommand.kt:41-83`
- Test: `mcp-steroid-server/.../server/` (add/adjust a resolver test), `npx-kt/.../FetchResourceCommandTest.kt:41`

**Interfaces:**
- Produces: `resolveResourceArticle(uri: String, context: PromptsContext): <Article>?` returning the article object (caller calls `.readPayload(context)`); `canonicalResourceEntryPoints(): List<<Article>>`. `<Article>` = the common supertype of `SkillPromptArticle` etc.

- [ ] **Step 1: Discover the article supertype name**

Run: `grep -rn "class SkillPromptArticle" --include='*.kt' .` and read its `: SuperType`. Use that concrete type in the signatures below (replace `Article` placeholder with the real type, e.g. `MarkdownArticle`). This is a real discovery step — do not invent the name.

- [ ] **Step 2: Add a failing test for the article-returning API**

In `mcp-steroid-server` add a test asserting `resolveResourceArticle(knownUri, PromptsContext.Generic)` returns non-null and its `.uri` equals the requested uri; and `canonicalResourceEntryPoints()` returns a non-empty list whose `.first().uri` is a valid `mcp-steroid://` string.

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew :mcp-steroid-server:test --tests '*FetchResource*'`
Expected: FAIL (function/return-type not present).

- [ ] **Step 4: Change the return types**

- `resolveResourcePayload(...): String?` → `resolveResourceArticle(...): <Article>?` returning the matched `article` (drop the `.readPayload` call here).
- `canonicalResourceEntryPoints(): List<String>` → `List<<Article>>` (return the article instances, not `.uri`).
- Update `FetchResourceToolHandler.call:70-77` to `val article = resolveResourceArticle(uri, promptsContext) ?: return error; ToolCallResult(content = listOf(ContentItem.Text(article.readPayload(promptsContext))))`.
- Update `FetchResourceCommand.kt:41` similarly; update the error-hint loop (`:73`) and `canonicalResourceEntryPointOrPlaceholder()` (`:83`) to use `entry.uri`.

- [ ] **Step 5: Update the npx-kt consumer test**

In `FetchResourceCommandTest.kt:41` change `canonicalResourceEntryPoints().first()` → `canonicalResourceEntryPoints().first().uri`.

- [ ] **Step 6: Run both modules' tests**

Run: `./gradlew :mcp-steroid-server:test --tests '*FetchResource*'` then `./gradlew :npx-kt:test --tests '*FetchResourceCommandTest*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/FetchResourceToolHandler.kt npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/FetchResourceCommand.kt npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/FetchResourceCommandTest.kt mcp-steroid-server/src/test/
git commit -m "refactor: fetch-resource resolution returns Article objects, not bare strings (C1,C2)"
```

---

## Task 8: `promptsContextFromRoute` helper + dedupe (comment C10, inline)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigPromptsContextHandler.kt`
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/FetchResourceCommand.kt:65`
- Test: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/` (add a small unit test)

**Interfaces:**
- Produces: `DevrigPromptsContextHandler.Companion.promptsContextFromRoute(route: ProjectRoute): PromptsContext` = `promptsContextFromBuild(route.route.ide.build)`. The `route.route.ide.build` navigation lives ONLY here.

- [ ] **Step 1: Add a failing test**

```kotlin
    @Test
    fun `promptsContextFromRoute derives product and baseline from the route build`() {
        val route = fakeRoute(build = "IU-261.1234")   // build a ProjectRoute with route.ide.build = "IU-261.1234"
        val ctx = DevrigPromptsContextHandler.promptsContextFromRoute(route)
        assertEquals("IU", ctx.productCode)
        assertEquals(261, ctx.baselineVersion)
    }
```

Build `fakeRoute` from the real `ProjectRoute`/`DiscoveredIde` constructors (inspect `DiscoveredIde` fields first; reuse any existing test fixture in the `server` test package).

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :npx-kt:test --tests '*PromptsContext*'`
Expected: FAIL (`promptsContextFromRoute` unresolved).

- [ ] **Step 3: Add the helper and dedupe both call sites**

In `DevrigPromptsContextHandler`:

```kotlin
    companion object {
        fun promptsContextFromRoute(route: ProjectRoute): PromptsContext =
            promptsContextFromBuild(route.route.ide.build)

        fun promptsContextFromBuild(build: String): PromptsContext { /* unchanged */ }
    }
```

Change `buildPromptsContext` (`:11-12`) to `return promptsContextFromRoute(routing.requireProject(projectName))`, and `FetchResourceCommand.resolvePromptsContext` (`:65`) to `return DevrigPromptsContextHandler.promptsContextFromRoute(route)`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :npx-kt:test --tests '*PromptsContext*' --tests '*FetchResourceCommandTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigPromptsContextHandler.kt npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/FetchResourceCommand.kt npx-kt/src/test/
git commit -m "refactor(devrig): add promptsContextFromRoute, hide route.route.ide.build (C10)"
```

---

## Task 9: Core reuse — call `ToolSpec.call()` instead of rebuilding `*Params` (comments C9 + C15)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/ToolBackedCommands.kt` (all five tool-backed runners)
- Possibly Create: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/CliToolDispatch.kt` (a helper that maps CLI args → `arguments: JsonObject`, builds a `ToolCallContext` over a no-arg `McpSession()` + `stderrProgressReporter()`, and calls the tool)
- Test: existing glue tests (`ExecuteCodeCommandTest`, `FeedbackAndInputCommandTest`, `ScreenshotAndOpenProjectCommandTest`, `CliGlueTestSupport`) kept green

**Interfaces:**
- Consumes: `McpSession()` (no-arg ctor), `ToolCallParams(name, arguments)`, `ToolCallContext(params, session, progress)`, each `*ToolSpec` (e.g. `ExecuteCodeToolSpec`), `StubMcpSteroidTools` (for handlers), `stderrProgressReporter()`.
- Produces: a single `suspend fun callToolViaSpec(spec: McpTool, arguments: JsonObject, progress: McpProgressReporter): ToolCallResult` that the runners use in place of the manual `*Params` construction + handler call.

**Design constraint (must hold):** do NOT route through `McpToolRegistry.callTool`, which swallows exceptions into a generic `isError` and would collapse the exit-code contract (`ProjectRouteNotFound` → USAGE 64 vs bridge failure → UNAVAILABLE 69). Call `spec.call(context)` directly and keep the CLI's existing `try/catch → renderCliError(...)` exit-code mapping around it (the `ProjectRouteNotFoundException` / generic-`Exception` branches in `runToolCall`). The observable `--json`/exit-code contract must be byte-for-byte unchanged.

- [ ] **Step 1: Baseline green + capture the current contract**

Run: `./gradlew :npx-kt:test`
Expected: PASS. These tests are the spec for this refactor — they must stay green with zero edits (behavior-preserving refactor).

- [ ] **Step 2: Add a characterization test that execute_code forwards args as tool JSON**

In `ExecuteCodeCommandTest.kt` (or `CliGlueTestSupport`), add a test that runs `runExecuteCodeCommand` with a `FakeMcpSteroidTools` and asserts the tool received `code`, `task_id`, `reason`, `timeout`, `modal` with the expected values — driving them through the new arguments-JSON path. Model it on the existing glue tests' fake-handler recording.

- [ ] **Step 3: Run it (may pass against current code)**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeCommandTest*'`
Expected: PASS or FAIL depending on whether the assertion matches the current path; either way it locks the arg-mapping contract before the refactor.

- [ ] **Step 4: Introduce `callToolViaSpec` and migrate `execute_code` first**

Add the dispatch helper:

```kotlin
suspend fun callToolViaSpec(
    spec: McpTool,
    arguments: JsonObject,
    progress: McpProgressReporter,
): ToolCallResult {
    val params = ToolCallParams(name = spec.name, arguments = arguments)
    val context = ToolCallContext(params, McpSession(), progress)
    return spec.call(context)
}
```

Rewrite `runExecuteCodeCommand` to build `arguments` from the CLI flags (`buildJsonObject { put("code", code); put("task_id", command.taskId); put("reason", command.reason); put("timeout", timeout); put("modal", modalWire); put("project_name", projectName) }`), then `callToolViaSpec(ExecuteCodeToolSpec { tools.handler() }, arguments, stderrProgressReporter())`, keeping the existing surrounding `try/catch → renderCliError` for exit codes and the `renderTo` at the end. Remove the hand-built `ExecCodeParams`.

- [ ] **Step 5: Run execute_code tests**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeCommandTest*' --tests '*McpAsCliContractTest*' --tests '*CliErrorEnvelopeTest*'`
Expected: PASS. Investigate any exit-code diff immediately — it means an exception path changed.

- [ ] **Step 6: Migrate the remaining four runners the same way**

Repeat Step 4's pattern for `execute_feedback`, `input`, `take_screenshot`, `open_project` (each builds its own `arguments` object and calls its `*ToolSpec`). Keep `--out` (screenshot post-write) and `--wait` (poll loop) as the existing hooks around the call. Delete the now-dead `*Params` imports.

- [ ] **Step 7: Full suite**

Run: `./gradlew :npx-kt:test`
Expected: PASS (all contract tests). Then `devrig-dev` spot-check per the memory note (build with `installDist`, run via the `devrig-dev` symlink): `execute_code`, `take_screenshot --json | jq`, a stale `--project_name` (expect USAGE 64), and an unreachable bridge (expect UNAVAILABLE 69).

- [ ] **Step 8: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/
git commit -m "refactor(devrig): route CLI tool calls through ToolSpec.call(), drop *Params rebuild (C9,C15)"
```

---

## Task 10: Optional `--project_name` via cwd inference — the resolver (issue #266, part 1)

**Files:**
- Create: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/CwdProjectResolver.kt`
- Create: `npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/CwdProjectResolverTest.kt`

**Interfaces:**
- Consumes: `DevrigProjectRoutingService.routes(): List<ProjectRoute>` (each has `projectPath: String`, `exposedProjectName: String`).
- Produces:

```kotlin
sealed interface CwdProjectMatch {
    data class One(val route: ProjectRoute) : CwdProjectMatch
    data object None : CwdProjectMatch
    data class Ambiguous(val candidates: List<ProjectRoute>) : CwdProjectMatch
}
fun resolveProjectFromCwd(cwd: Path, routes: List<ProjectRoute>): CwdProjectMatch
```

Longest path-segment-boundary prefix wins. `/foo/bar` must NOT match a route at `/foo/barbaz`. If several routes tie at the same longest depth → `Ambiguous`.

- [ ] **Step 1: Write the resolver test matrix**

```kotlin
class CwdProjectResolverTest {
    private fun route(path: String, name: String) = /* build a ProjectRoute with projectPath=path, exposedProjectName=name */

    @Test fun `single project containing cwd matches`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.One(r), resolveProjectFromCwd(Path.of("/home/u/proj/src"), listOf(r)))
    }
    @Test fun `cwd equal to project root matches`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.One(r), resolveProjectFromCwd(Path.of("/home/u/proj"), listOf(r)))
    }
    @Test fun `nested projects pick the most specific`() {
        val outer = route("/home/u/proj", "proj-abc")
        val inner = route("/home/u/proj/module", "module-def")
        assertEquals(CwdProjectMatch.One(inner),
            resolveProjectFromCwd(Path.of("/home/u/proj/module/src"), listOf(outer, inner)))
    }
    @Test fun `no containing project yields None`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.None, resolveProjectFromCwd(Path.of("/tmp/elsewhere"), listOf(r)))
    }
    @Test fun `sibling prefix does not falsely match`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.None, resolveProjectFromCwd(Path.of("/home/u/projbeta"), listOf(r)))
    }
}
```

Fill `route(...)` from the real `ProjectRoute` constructor (reuse the fixture from Task 8 if shared).

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :npx-kt:test --tests '*CwdProjectResolverTest*'`
Expected: FAIL (unresolved `resolveProjectFromCwd`).

- [ ] **Step 3: Implement the resolver**

```kotlin
fun resolveProjectFromCwd(cwd: Path, routes: List<ProjectRoute>): CwdProjectMatch {
    val abs = cwd.toAbsolutePath().normalize()
    val containing = routes.filter { abs.startsWith(Path.of(it.projectPath).toAbsolutePath().normalize()) }
    if (containing.isEmpty()) return CwdProjectMatch.None
    val maxDepth = containing.maxOf { Path.of(it.projectPath).normalize().nameCount }
    val deepest = containing.filter { Path.of(it.projectPath).normalize().nameCount == maxDepth }
    return if (deepest.size == 1) CwdProjectMatch.One(deepest.single())
        else CwdProjectMatch.Ambiguous(deepest)
}
```

`Path.startsWith` compares on name boundaries, so the sibling-prefix case is handled correctly.

- [ ] **Step 4: Run tests**

Run: `./gradlew :npx-kt:test --tests '*CwdProjectResolverTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/CwdProjectResolver.kt npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/CwdProjectResolverTest.kt
git commit -m "feat(devrig): cwd->project longest-prefix resolver (#266 part 1)"
```

---

## Task 11: Wire cwd inference into the project-scoped commands (issue #266, part 2)

**Files:**
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/ToolBackedCommands.kt` (the project_name resolve point created in Task 9)
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/Cli.kt` (help text for the four commands)
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/HelpCommand.kt` (note optional `--project_name`)
- Test: `npx-kt/.../ExecuteCodeCommandTest.kt`, `FeedbackAndInputCommandTest.kt`, `ScreenshotAndOpenProjectCommandTest.kt`

**Interfaces:**
- Consumes: `resolveProjectFromCwd`, `CwdProjectMatch`, `DevrigServices` (has `projectRouting`; get cwd from `System.getProperty("user.dir")` — confirm devrig's cwd source, it is the launch dir).
- Produces: a shared `fun DevrigServices.requireProjectName(explicit: String?): String` that returns `explicit` when non-blank, else infers from cwd, else throws a `CodeArgException`/routes through `renderCliError` with an agent-understandable candidate list.

- [ ] **Step 1: Add failing tests for inference + override + errors**

In `ExecuteCodeCommandTest.kt`:

```kotlin
    @Test fun `explicit project_name overrides cwd inference`() { /* pass --project_name, assert the tool got it verbatim */ }
    @Test fun `blank project_name infers the single containing project`() { /* fake routes with one match under cwd, assert inferred name reaches the tool */ }
    @Test fun `no containing project fails with a candidate-listing usage error`() { /* assert USAGE(64) envelope lists open projects */ }
```

Use the existing fake-tools harness; inject a `routes()` snapshot and a cwd.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeCommandTest*'`
Expected: FAIL.

- [ ] **Step 3: Make `--project_name` optional + add `requireProjectName`**

- In `Cli.kt`, the four commands' `--project_name` options are already `String?` — update their `help` to: "routing key from `devrig list_projects`; omit to infer from the current directory".
- Add `requireProjectName(explicit)` that maps `CwdProjectMatch.One` → `route.exposedProjectName`; `None`/`Ambiguous` → throw a usage error whose message lists `routes().map { it.exposedProjectName }` and asks for an explicit `--project_name`.
- At the single project_name resolve point in each of the four runners, call `requireProjectName(command.projectName)` instead of using the raw (previously-required) value.

- [ ] **Step 4: Run tests**

Run: `./gradlew :npx-kt:test --tests '*ExecuteCodeCommandTest*' --tests '*FeedbackAndInputCommandTest*' --tests '*ScreenshotAndOpenProjectCommandTest*' --tests '*McpAsCliContractTest*'`
Expected: PASS.

- [ ] **Step 5: Update help docs**

Add a line to `printExecuteCodeHelp` and the global banner: `--project_name` is optional; inferred from cwd when omitted; required when run outside any open project or when the directory is inside more than one.

- [ ] **Step 6: Full suite + devrig-dev spot-check**

Run: `./gradlew :npx-kt:test`
Expected: PASS. Spot-check with `devrig-dev`: `cd` into a known open project and run `execute_code` without `--project_name` (should infer); run from `/tmp` (should list candidates + exit 64).

- [ ] **Step 7: Commit**

```bash
git add npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/ npx-kt/src/test/kotlin/com/jonnyzzz/mcpSteroid/devrig/
git commit -m "feat(devrig): infer --project_name from cwd, explicit overrides (#266 part 2)"
```

---

## Task 12: PR reply for the self-heal gate + follow-up issue (comments C13/C14, C8/C11/C12)

**Files:** none (GitHub only).

- [ ] **Step 1: Post a reply on the `Main.kt:97` thread**

Explain that `selfHealsLauncherOnStart()` gating is intrinsic to this PR (the new MCP-as-CLI tool facades must not mutate launcher/PATH state — Tenet 3 — and it is pinned by `LauncherSelfHealPredicateTest`, Round-5 #9). Ask which of the "several related issues" to the launcher logic he wants split into a separate PR rather than extracting the load-bearing gate.

```bash
gh pr comment 272 --body "<reply text>"
```

(Use a review-thread reply if preferred: `gh api ...pulls/272/comments/<id>/replies`.)

- [ ] **Step 2: Open the follow-up issue for schema-driven generation**

```bash
gh issue create --title "devrig CLI: generate subcommands/flags/help from MCP ToolSpec" \
  --body "Follow-up to #272 (comments C8/C11/C12). Derive clikt options, per-command help, and command registration from each tool's InputSchemaElement specs so a new MCP tool auto-registers as a CLI command. Needs: schema->clikt type adapters, a help synopsis (not the multi-KB tool description), and re-validation of the frozen --json/exit-code contract. Epic #188."
```

- [ ] **Step 3: Note the issue number in `TODO-mcp-as-cli.md`** (Round 6 → "Deferred to a FOLLOW-UP PR"), then commit that doc.

```bash
git add TODO-mcp-as-cli.md
git commit -m "docs: record PR #272 review-response plan (Round 6) + follow-up issue ref"
```

---

## Task 13: Final verification

- [ ] **Step 1: Full module suites green**

Run: `./gradlew :npx-kt:test` then `./gradlew :mcp-steroid-server:test`
Expected: BUILD SUCCESSFUL, all green (incl. the ~1393-test contract suite).

- [ ] **Step 2: Compile/warning check via IDE MCP**

Per `CLAUDE.md`, verify no new warnings/errors with `steroid_execute_code` (`runInspectionsDirectly` / build) on the changed files.

- [ ] **Step 3: devrig-dev end-to-end spot check**

Build (`./gradlew :npx-kt:installDist`) and drive via the `devrig-dev` symlink: `prompt <uri>`, `execute_code` (inferred + explicit project), `take_screenshot` (file path printed + `--json` data present), a stale project (USAGE 64), an unreachable bridge (UNAVAILABLE 69). Confirm each `--json` output is a single `jq`-valid document.

- [ ] **Step 4: Reply to the remaining review threads**

For each addressed comment, reply on its thread with the commit that resolved it, then re-request review.

## Self-Review Notes

- **Spec coverage:** all 18 comments mapped — C16→T1, C5→T2, C17→T3, C6/C7→T4, C3→T5, C4→T6, C1/C2→T7, C10→T8, C9/C15→T9, #266→T10+T11, C13/C14→T12, C8/C11/C12→T12 (deferred issue). ✅
- **Contract safety:** T4, T5, T6 rewrite the tests that pinned the OLD image/envelope contract to pin the NEW one (intentional contract change, recorded in commits). T9 is behavior-preserving — its tests must pass unedited. ✅
- **Type consistency:** `Presentation` (T5) provides the `imageDir` seam T6 needs; `callToolViaSpec` (T9) provides the single project_name resolve point T11 needs; `promptsContextFromRoute` (T8) and `resolveProjectFromCwd` (T10) both consume the routing snapshot. ✅
- **Discovery steps** (article supertype in T7; `DiscoveredIde`/`ProjectRoute` fixtures in T8/T10) are explicit executable steps, not placeholders. ✅
