IDE: Apply a Unified Diff with Tolerance Matching

Apply a unified diff via the IDE's drift-tolerant patch engine (`GenericPatchApplier`). For COMPLEX changes only — the primary edit flow stays the plain read-replace-save script.

## When to use this — and when not to

**Not the major flow.** For ordinary edits — one or a handful of literal substitutions —
use the read-replace-save shape (`content.replace(OLD, NEW)` + `writeAction { VfsUtil.saveText(...) }`,
see `mcp-steroid://skill/execute-code-tool-description`, "Multi-site edits"). It is simpler,
fully predictable, and the anchor-safe recipe (`mcp-steroid://skill/anchor-safe-editing`)
makes it land on the first attempt.

Reach for this recipe only when:

- you **already have a unified diff** (from `git diff`, a patch file, an upstream PR, a
  generated changeset) and want it applied without hand-translating hunks into literal
  replacements;
- a **complex multi-hunk change** keeps failing literal-anchor matching because the file
  drifted (formatting, surrounding edits) — the patch engine's context lines absorb what an
  exact `replace` cannot.

## The recipe

`PatchReader` parses standard unified-diff text; `GenericPatchApplier.apply` runs the
tolerance ladder per file and returns the patched text plus a status. All classes are
public platform API (`com.intellij.openapi.diff.impl.patch`), available in every
JetBrains IDE.

```kotlin
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.ApplyPatchStatus
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier

val diffText = """
--- a/src/main/java/com/example/Service.java
+++ b/src/main/java/com/example/Service.java
@@ -10,7 +10,8 @@
 public class Service {
     public int compute(int x) {
-        return x * 2;
+        // doubled, then offset
+        return x * 2 + 1;
     }
 }

""".trimIndent()

val reader = PatchReader(diffText)
reader.parseAllPatches()

for (patch in reader.textPatches) {
    check(!patch.isNewFile && !patch.isDeletedFile) {
        "this recipe applies UPDATE hunks only — create files via VfsUtil / delete via vf.delete(this) in a writeAction"
    }
    val relativePath = patch.beforeName ?: patch.afterName ?: error("patch without a file name")
    val vf = findProjectFile(relativePath) ?: error("not found: $relativePath")
    val text = String(vf.contentsToByteArray(), vf.charset)

    val applied = GenericPatchApplier.apply(text, patch.hunks)
        ?: error("patch did not apply to $relativePath — hunks could not be matched")
    check(applied.status == ApplyPatchStatus.SUCCESS || applied.status == ApplyPatchStatus.ALREADY_APPLIED) {
        "patch applied with status ${applied.status} on $relativePath — inspect before writing"
    }
    if (applied.status == ApplyPatchStatus.ALREADY_APPLIED) {
        println("ALREADY APPLIED: $relativePath — skipping write")
        continue
    }
    writeAction { VfsUtil.saveText(vf, applied.patchedText) }
    println("PATCHED: $relativePath (${patch.hunks.size} hunks, status ${applied.status})")
}
```

Verify after applying — re-read the files and check the expected text, or run the affected
tests. The engine guarantees a match was found, not that the result is semantically right.

## Status handling

- `SUCCESS` — every hunk matched (possibly at a shifted position). Write the result.
- `ALREADY_APPLIED` — the file already contains the patched state; skip the write. This is
  the engine's idempotency: re-applying a patch is a no-op, not an error.
- `null` return — some hunk could not be matched at all. Do NOT fall back to
  `GenericPatchApplier.applySomehow(...)` blindly: it force-places mismatched hunks and can
  corrupt the file. Instead, regenerate the diff against the CURRENT file content and retry.

## Caveats

- **Update hunks only.** A `git diff` can carry file creations (`--- /dev/null`) and deletions
  (`+++ /dev/null`); this recipe guards against both — without the guard a creation fails with a
  misleading "not found" and a deletion would EMPTY the file while reporting success. Create and
  delete files through the VFS APIs instead.
- **Context lines matter.** The tolerance ladder works off the hunk's context lines — give
  each hunk 2-3 unchanged lines around the change (standard `git diff` output already does).
  A context-free diff degrades to exact matching.
- **Paths in the diff** are resolved here via `findProjectFile`, so both project-relative
  (`a/src/...` prefixes stripped by `PatchReader`) and absolute paths work.
- **One write per file, inside `writeAction { }`** — same threading rule as every VFS write
  (`mcp-steroid://skill/coding-with-intellij-threading`).

# See also

- [Execute Code Tool Description](mcp-steroid://skill/execute-code-tool-description) — the primary multi-site edit flow
- [Anchor-Safe Editing](mcp-steroid://skill/anchor-safe-editing) — make literal replacements land on the first attempt
- [Threading and Read/Write Actions](mcp-steroid://skill/coding-with-intellij-threading)
- [VFS access](mcp-steroid://skill/coding-with-intellij-vfs)
