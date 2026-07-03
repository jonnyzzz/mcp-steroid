IDE Examples Overview

Overview of IntelliJ IDE power operation examples.

# IntelliJ IDE Power Operations (Non-LSP)

This collection provides example code snippets implementing advanced IDE operations
that go beyond LSP capabilities. Each example is a complete script for `steroid_execute_code`.

## Available Examples

### Refactorings

| Resource | Operation | Description |
|----------|-----------|-------------|
| `mcp-steroid://ide/extract-method` | Extract Method | Extract selected statements into a new method |
| `mcp-steroid://ide/introduce-variable` | Introduce Variable | Extract expression into a new local variable |
| `mcp-steroid://ide/inline-method` | Inline Method | Inline method body at call sites |
| `mcp-steroid://ide/change-signature` | Change Signature | Add/reorder parameters and update call sites |
| `mcp-steroid://ide/move-file` | Move File | Move a file to another directory and update references |
| `mcp-steroid://ide/apply-unified-diff` | Apply a Unified Diff (tolerance matching) | The IDE's own patch engine for COMPLEX changes / existing diffs — not the primary edit flow |
| `mcp-steroid://ide/safe-delete` | Safe Delete | Safely remove elements with usage analysis |
| `mcp-steroid://ide/pull-up-members` | Pull Up Members | Move members to a base class |
| `mcp-steroid://ide/push-down-members` | Push Down Members | Move members to subclasses |
| `mcp-steroid://ide/extract-interface` | Extract Interface | Create an interface from a class |
| `mcp-steroid://ide/move-class` | Move Class / Package | Move classes between packages |

### Code Hygiene

| Resource | Operation | Description |
|----------|-----------|-------------|
| `mcp-steroid://ide/optimize-imports` | Optimize Imports | Remove unused imports and sort remaining ones |
| `mcp-steroid://ide/inspect-and-fix` | Inspection + Fix | Run an inspection and apply a quick fix |
| `mcp-steroid://ide/inspection-summary` | Inspection Summary | List enabled inspections in the project |
| `mcp-steroid://ide/find-duplicates` | Find Duplicate Code | Run `DuplicatedCode` and walk every clone cluster (main + duplicates) typed |

### Navigation and Generation

| Resource | Operation | Description |
|----------|-----------|-------------|
| `mcp-steroid://ide/generate-override` | Generate Overrides | Implement interface methods / override base methods |
| `mcp-steroid://ide/hierarchy-search` | Hierarchy Search | Find inheritors and overrides for a class/method |
| `mcp-steroid://ide/call-hierarchy` | Call Hierarchy | Find method callers |
| `mcp-steroid://ide/generate-constructor` | Generate Constructor | Create constructors from fields |

### Project Intelligence

| Resource | Operation | Description |
|----------|-----------|-------------|
| `mcp-steroid://ide/project-dependencies` | Project Dependencies | Summarize module dependencies |
| `mcp-steroid://ide/project-search` | Project Search (Index) | Search files by name or file type |
| `mcp-steroid://ide/run-configuration` | Run Configuration | List and execute run configs |
| `mcp-steroid://ide/demo-debug-test` | Demo Debug Test | End-to-end debug run with test results |

## Usage

1. Read a specific example resource to get the complete code snippet.
2. Adapt the code to your needs (file paths, positions, names).
3. Execute via `steroid_execute_code`.

## Notes

- Many examples support `dryRun` to preview changes safely.
- For write operations, use `dryRun=true` first, then set to `false` to apply.
- Under the default `modal=smart_non_modal`, `waitForSmartMode()` runs automatically before your script starts (skipped under `non_modal` / `unleashed`); call it again only after triggering indexing.

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Test execution

### IDE Power Operations
- Refactorings: `extract-method`, `introduce-variable`, `inline-method`, `change-signature`, `move-file`, `safe-delete`, `pull-up-members`, `push-down-members`, `extract-interface`, `move-class`
- Code Hygiene: `optimize-imports`, `inspect-and-fix`, `inspection-summary`, `find-duplicates`
- Navigation & Generation: `generate-override`, `hierarchy-search`, `call-hierarchy`, `generate-constructor`
- Project Intelligence: `project-dependencies`, `project-search`, `run-configuration`

See `mcp-steroid://ide/<id>` for specific examples (e.g., `mcp-steroid://ide/extract-method`)

### Related Example Guides
- [LSP Examples](mcp-steroid://lsp/overview) - LSP-like operations (navigation, code intelligence, refactoring)
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [Test Examples](mcp-steroid://test/overview) - Test execution
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
