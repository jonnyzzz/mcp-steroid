Structural search for Kotlin — K2 profile, custom filters, limitations

Kotlin K2 profile specifics: the four custom `_AlsoMatch*` filters, predefined templates, async FQN shortening, and 12 documented limitations.

# Kotlin SSR (K2)

The Kotlin language profile is registered as `org.jetbrains.kotlin.idea.k2.codeinsight.structuralsearch.KotlinStructuralSearchProfile` (module `community/plugins/kotlin/code-insight/structural-search-k2/`). It is loaded automatically by IntelliJ — agent scripts only need to set `MatchOptions.setFileType(KotlinFileType.INSTANCE)`.

Type resolution runs through the Kotlin Analysis-API: every type-aware predicate (e.g. `:[exprtype(...)]`) walks `analyze(node) { … }` blocks that resolve `KaType` instances. Type rendering uses `KaTypeRendererForSource.WITH_SHORT_NAMES` and `WITH_QUALIFIED_NAMES`, supertype walks use `KaType.allSupertypes`. None of this matters for skill recipe authors except as background: skill recipes use the apostrophe-form template language, exactly like Java.

**Asynchronous FQN shortening** — the only K2 behaviour a script must account for. `KotlinStructuralReplaceHandler.postProcess` does not call `ShortenReferences.DEFAULT.process(...)` synchronously; it submits a non-blocking read action `analyze { collectPossibleReferenceShorteningsInElementForIde(...) }`, then an EDT `invokeShortening()` runs under a separate write command. **A script that reads file contents immediately after `Replacer.replace(info)` may still see fully-qualified names.** Set `isToShortenFQN = false` and run shortening explicitly afterwards if you need deterministic, immediately-visible output.

## Pattern variables

Internal typed-var prefix: `_____` (five underscores). User syntax (apostrophe form): `'name` (target) or `'_name` (non-target). The compiler rewrites `'_x` → `$x$` in the compiled pattern; the language profile parses `$x$` placeholders into `KtNameReferenceExpression` / `KtParameter` PSI nodes whose text is `_____x`.

Example PSI dump for the template `runCatching { '_BODY* }.onFailure { '_E -> '_HANDLER* }`:

```
KtDotQualifiedExpression
├── KtCallExpression "runCatching { _____BODY }"
│   ├── KtNameReferenceExpression "runCatching"
│   └── KtLambdaArgument
│       └── KtLambdaExpression / KtFunctionLiteral
│           └── KtBlockExpression
│               └── KtNameReferenceExpression "_____BODY"
└── KtCallExpression "onFailure { _____E -> _____HANDLER }"
    └── KtLambdaArgument
        └── KtLambdaExpression / KtFunctionLiteral
            ├── KtParameterList
            │   └── KtParameter "_____E"
            └── KtBlockExpression
                └── KtNameReferenceExpression "_____HANDLER"
```

## Pattern contexts

Two contexts (`MatchOptions.patternContext`):

- `default` — full-statement / expression / declaration patterns. Parsed via `KtPsiFactory.createBlock(text)`.
- `property` — the property-getter/setter context. Parsed via `KtPsiFactory.createProperty(text)`. Used by the predefined "Properties with explicit getter" template.

Setting the wrong context silently produces zero matches. If your template starts with `var '_x = ... \n get() = ...`, set `matchOptions.patternContext = profile.patternContexts.first { it.id == "property" }`.

## Custom filter macros (Kotlin only)

These are `_`-prefixed inline macros routed via `MatchVariableConstraint.putAdditionalConstraint(...)` to Kotlin-specific predicates. They are not part of the platform `knownOptions` set.

| Macro | Effect | Where it applies |
|---|---|---|
| `_AlsoMatchVal( ENABLED )` | A `var` pattern also matches `val` declarations | On a `var` `KtProperty` |
| `_AlsoMatchVar( ENABLED )` | A `val` pattern also matches `var` declarations | On a `val` `KtProperty` |
| `_AlsoMatchCompanionObject( ENABLED )` | A non-companion `object` pattern also matches a companion object | On a non-companion `KtObjectDeclaration` |
| `_MatchCallSemantics( ENABLED )` | Argument-order-insensitive matching for `KtCallElement` (named/positional argument equivalence) | On a typed-var whose grandparent is a `KtCallElement` |

Examples shipped in IntelliJ's Kotlin predefined templates:

```
'_:[_MatchCallSemantics( ENABLED )](true, 0, 1)        # matches A(true, 0, 1), A(b=true, c=0, d=1), A(c=0, d=1, b=true), A(true, d=1, c=0)
var '_x:[_AlsoMatchVal( ENABLED )] = '_init            # also matches val declarations
object '_o:[_AlsoMatchCompanionObject( ENABLED )] {}   # also matches companion objects
```

## Predefined Kotlin templates

The Kotlin profile ships 34 templates across categories `class`, `expressions`, `declaration`, `operators`, `comments`, `interesting`. Highlights worth quoting:

```
# Class — all vars of a class
class '_Class { var 'Field+ = '_Init? }

# Expressions — method calls with type filter
'_Before?:[exprtype( pkg.MyClass )].'MethodCall('_Parameter*)

# Expressions — vars of a given type
var '_Variable:[exprtype( Int )] = '_Init

# Declarations — explicit/inferred type (count constraint)
fun '_Name('_Param*) : '_Type{0,1}

# Operators — try/catch/finally skeleton
try { '_Body* } catch ('_E: '_T) { '_Handler* } finally { '_Cleanup* }

# Interesting — properties with explicit getter (PROPERTY_CONTEXT)
var '_Inst = '_Expr
    get() = '_Getter
```

For the full live list:

```kotlin[AI,IC,IU]
import com.intellij.structuralsearch.StructuralSearchUtil
import com.intellij.openapi.fileTypes.LanguageFileType
val kotlinFileType: LanguageFileType = org.jetbrains.kotlin.idea.KotlinFileType.INSTANCE
val profile = StructuralSearchUtil.getProfileByFileType(kotlinFileType)!!
profile.predefinedTemplates.forEach {
    println("${it.category}/${it.name}: ${it.matchOptions.searchPattern.take(120)}")
}
```

## Documented limitations

These are real gaps observed in the profile sources and the test corpus. If your skill recipe needs any of them, work around using a `script` filter or accept that the pattern won't match.

1. **No top-level visibility constraint.** Filter by writing the keyword in the pattern (e.g. `private fun '_x()`); there is no `:[visibility(...)]` predicate.
2. **No contracts awareness.** `kotlin.contracts.contract { … }` blocks match as ordinary calls; no predicate for `returns`/`callsInPlace`.
3. **No `expect`/`actual` cross-module link.** The matcher does not link an `expect` declaration to its `actual` counterpart; searching for one will not find the other.
4. **No smart-cast type predicate.** `exprtype(...)` reports the declared/inferred type at the reference site; the smart-cast type is not visible.
5. **No nullability-only constraint.** Express it inside the type literal: `exprtype(Int?)` vs `exprtype(Int)`. There is no "any nullable" wildcard.
6. **No receiver-type predicate as a separate constraint.** Receiver type is matchable only via the dot-qualified pattern shape and `exprtype(...)` on the receiver position.
7. **`KtPackageDirective` is unconditionally skipped** by `KotlinMatchingStrategy.shouldSkip`. SSR cannot search package declarations or imports.
8. **`_MatchCallSemantics` is a no-op as a predicate** — the actual call-semantics matching is implemented inside `KotlinMatchingVisitor` for `KtCallElement`. Consequence: scripted custom predicates cannot post-filter call-semantics matches.
9. **`PROPERTY_CONTEXT` parses via `factory.createProperty(text)`** — a pattern that is not a single `KtProperty` returns `PsiElement.EMPTY_ARRAY`, so the wrong context silently produces zero matches.
10. **Replacement requires shape parity.** `checkReplacementPattern` rejects search/replacement pairs where one is a `KtDeclaration` and the other is not.
11. **Standalone-type / standalone-annotation parsing is fragile.** `createPatternTree` has explicit fallbacks for nullable types and user-types-with-type-parameters that silently swallow `KotlinExceptionWithAttachments`. Edge-case patterns may parse as a comment with attached `PATTERN_ERROR` user-data instead of a usable tree.
12. **No KDoc reference (`[Foo.bar]`) constraint** beyond raw text matching of `@'_Tag '_Text` — `KDocLink` PSI is reachable but `isApplicableConstraint` does not register a reference predicate for KDoc nodes.

## Lambda parameters: explicit `'_E ->` vs implicit `it`

Lambdas with an explicit parameter list (`{ e -> … }`) and lambdas with the implicit `it` (`{ … }`) have **different PSI shapes** — the explicit form has a `KtParameterList`, the implicit form does not. A pattern that includes `'_E ->` will only match lambdas with an explicit parameter; it does NOT match `it`-style lambdas. If you need to match both, write two patterns (or use a `:[script(...)]` filter on the lambda).

Practical advice: include `'_E ->` when the audit is about **what the parameter is named or typed**; omit it (e.g. `{ '_HANDLER* }`) when only the body matters and the project uses `it` consistently.

## `setRecursiveSearch(true)` — recommended for Kotlin call patterns

Like the Java canonical recipe, the Kotlin example below sets `setRecursiveSearch(true)` so the matcher descends into nested expressions (a `runCatching` chain inside another lambda body, a method call inside an `if` branch, etc.). Default is `false`, which only matches at the top of each statement — frequently NOT what an audit recipe wants. Set it explicitly per the canonical recipe in [api-recipe](mcp-steroid://skill/structural-search-api-recipe).

## Canonical Kotlin example — `runCatching{}.onFailure{}` audit

⚠️ **Search-only by default.** Rewriting to `try/catch` drops the `Result<T>` return value of the original chain. The pattern below specifically matches the `.onFailure { … }` shape — it does NOT match `runCatching { … }.getOrElse { … }`, `runCatching { … }.getOrThrow()`, `runCatching { … }.let { … }`, etc. because `onFailure` is a literal method-name anchor (see [syntax](mcp-steroid://skill/structural-search-syntax) "literal identifiers anchor exactly"). When the result IS consumed via `.getOrElse`/`.getOrThrow`/`.let`, those callsites are intentionally excluded — but they are also the ones that would be unsafe to rewrite, so the exclusion lines up with the safety story.

⚠️ **`'_E ->` matches explicit parameters only.** The lambda parameter `'_E ->` only matches `{ e -> … }`-style lambdas; it does NOT match `{ … }`-style lambdas using the implicit `it`. To match both shapes, write two patterns or replace `'_E -> '_HANDLER*` with just `'_HANDLER*` (and accept that the parameter is then unconstrained).

```kotlin[AI,IC,IU]
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink
import org.jetbrains.kotlin.idea.KotlinFileType

val opts = MatchOptions().apply {
    setFileType(KotlinFileType.INSTANCE)
    fillSearchCriteria("""
        runCatching {
            '_TRYBODY*
        }.onFailure { '_E ->
            '_HANDLER*
        }
    """.trimIndent())
    setRecursiveSearch(true)            // descend into nested expressions; see note above
    setSearchInjectedCode(false)
    setScope(GlobalSearchScope.projectScope(project))
}
readAction { Matcher.validate(project, opts) }
val sink = CollectingMatchResultSink()
Matcher(project, opts).findMatches(sink)
println("found ${sink.matches.size} runCatching/onFailure pairs")
```

If, after auditing, you decide a subset is safe to rewrite, follow the full replace flow in [api-recipe](mcp-steroid://skill/structural-search-api-recipe) — including `setSearchInjectedCode(false)`, `Matcher.validate` first, the singular `Replacer.replace(info)` path, and `isToShortenFQN = false` if the script reads file contents immediately after the write (K2 async).

## Cross-references

- [Canonical API recipe](mcp-steroid://skill/structural-search-api-recipe)
- [Template language and macros](mcp-steroid://skill/structural-search-syntax)
- [Language coverage matrix](mcp-steroid://skill/structural-search-coverage)
- [Use-case gallery](mcp-steroid://skill/structural-search-use-cases)
