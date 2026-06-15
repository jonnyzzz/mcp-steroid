Structural search template language and macros

Pattern variable forms (apostrophe vs dollar), quantifiers, the nine inline macros, the leading-bracket context form, and the PSI-tree mental model.

# SSR template language

A template is real source code in the chosen language with placeholder identifiers. The language profile parses it into a PSI subtree; the matcher walks pattern and candidate trees in lockstep and binds variables to the subtrees they match.

## Three working forms — and which one parses inline constraints

```
form: '_x:[regex( foo )]              ← apostrophe form (full feature set)
form: $x$                              ← dollar form (storage / compiled output)
form: [within(...)]<actual template>   ← leading-bracket context form
```

`MatchOptions.fillSearchCriteria("...")` only scans for the apostrophe character to detect variables. It rewrites each `'name` into `$name$` and pulls every `:[…]` constraint out into a `MatchVariableConstraint`. Dollar-form characters in the input pass through verbatim and never trigger inline-constraint parsing, but the language profile **does** still recognise `$name$` as a typed-var when it builds the PSI tree — so a dollar-form pattern matches successfully with no constraints attached (it acts like "match anything").

| Form | Variables registered? | Inline `:[…]` parsed? | When you reach for it |
|---|---|---|---|
| Apostrophe `'_x:[…]` | yes | yes | When you author a template programmatically and need any constraint, quantifier, or anonymous variable |
| Dollar `$x$` | only via `addVariableConstraint(...)` calls | no | When consuming an existing `Configuration` (predefined templates, saved inspections) where constraints are already attached |
| Mixed | per-variable | per-variable | Rare; mostly an artefact of editing predefined templates |

**Default rule for skill recipes**: write the apostrophe form. The dollar form is the compiled output the engine emits, and the on-disk persistence form — not the input form humans (or scripts) should author by hand.

## Quantifiers

```
'_x        # exactly 1 (min=1, max=1, default)
'_x?       # 0 or 1
'_x+       # 1 or more
'_x*       # 0 or more
'_x{3}     # exactly 3
'_x{2,5}   # 2..5
'_x{2,}    # 2..MAX
'_x*?      # non-greedy variant (trailing ?)
```

**On argument/parameter lists**: `foo('_args*)` matches calls with zero or more
arguments — both `foo()` and `foo(x, y, z)`. Same for `class C('_p*) { … }` (zero
or more constructor parameters), method declarations `'_T '_m('_p*) { … }`, and
type-argument lists `Map<'_K*, '_V*>`. A bare `'_args` (no quantifier) requires
exactly one argument, so `foo('_args)` would NOT match `foo()`.

## The nine inline macros

Inside `:[…]` you compose constraints with `&&` (no `||` — use a script filter or a separate template):

```
'_x:[regex( <pattern> )]               # text matches Java regex
'_x:[regexw( <pattern> )]              # text matches AND wholeWordsOnly
'_x:[!regex( <pattern> )]              # NOT (text matches)
'_x:[regex( *<pattern> )]              # text within type hierarchy

'_x:[exprtype( java\.lang\.String )]                  # resolved expression type matches FQN exactly
'_x:[exprtype( *Number )]                             # exprtype within hierarchy (subtype of Number)
'_x:[exprtype( ~java\.util\.Optional<.*> )]           # ⚠ ~ REQUIRED when arg has .*, +, |, <.*>; without ~ the constraint is exact-FQN compare and silently matches NOTHING
'_x:[!exprtype( void )]                               # NOT (exprtype matches)

'_x:*Foo                               # shorthand for '_x:[regex( *Foo )] — sets withinHierarchy=true and regex='Foo'
'_x:+Foo                               # shorthand for strictlyWithinHierarchy (only proper subtypes/supertypes)
'_x:!Foo                               # shorthand for '_x:[!regex( Foo )] — invert regex
'_x:Foo                                # shorthand for '_x:[regex( Foo )] — bare regex after the colon
```

> ⚠️ **`'_I:*Greeting` is NOT a Java-language operator** — it is the bare-regex shorthand for `'_I:[regex( *Greeting )]`, which sets `MatchVariableConstraint.setWithinHierarchy(true)` plus regex `Greeting`. The engine resolves the matched reference to its type and walks the supertype chain; if any type's name matches `Greeting`, it's a match. Combined with the "Java `implements` matches both `implements` and `extends`" rule below, `class '_C implements '_I:*Greeting {}` finds direct implementors, direct subclasses, AND transitive ones in one query — see use-case [F1](mcp-steroid://skill/structural-search-use-cases).

```

'_x:[formal( java\.util\.List )]       # expected/formal-arg type (Java)
'_x:[ref( SavedTemplateName )]         # variable resolves to a reference saved as another template
'_x:[script( "x.text.length() > 5" )]  # Groovy filter — x is bound to PSI; can NOT be inverted
'_x:[contains( "'_y;" )]               # subtree contains a match of the inner pattern
'_:[within( "if ('_c) { '_st*; }" )]   # ONLY on __context__ — whole match must live inside the outer pattern
'_:[context( SavedContextTemplate )]   # ONLY on __context__ — runs in the named context
```

> ⚠️ **Common pitfall — `exprtype` and parameterized types.** Without the `~` prefix, the `exprtype` argument is an **exact-FQN string compare**. So `:[exprtype( java\.util\.Optional<.*> )]` does NOT match `Optional<String>` callsites — `<.*>` is regex syntax that goes through unchanged into an exact compare and never matches. Whenever the arg contains `.*`, `+`, `|`, `<.*>`, or any other regex metacharacter, the `~` prefix is REQUIRED: `:[exprtype( ~java\.util\.Optional<.*> )]`. Same rule for `formal(...)`. Symptom of the bug: `Matcher.validate` passes, `findMatches` quietly returns 0 — the "silent-zero-match" failure mode the [api-recipe](mcp-steroid://skill/structural-search-api-recipe) §4.1 warns about.

### Worked example — `Optional.get()` callsite audit

A concrete recipe that ties together the apostrophe form, the `~` prefix, the expression template (no trailing `;`), and the typed-receiver shape:

```kotlin[AI,IC,IU]
// Recommended: triple-quoted Kotlin string — write the SSR backslashes verbatim.
val pattern = """'_opt:[exprtype( ~java\.util\.Optional<.*> )].get()"""
//                ^^^^                                          ^^^^^^
//                receiver typed-var with exprtype filter        literal method name
//                                                              EXPRESSION template (no trailing ;)
```

Notes:

1. The pattern is an **expression template** (no trailing `;`) because `Optional.get()` callsites appear inside expressions: as an argument, RHS of `=`, return value, etc. Trailing `;` would lock the pattern to statement contexts only — see "Expression vs statement patterns" below.
2. `~` prefix on `exprtype(...)` is required because `<.*>` is regex syntax. Without `~`, the constraint is exact-FQN compare and silently matches zero.
3. **Kotlin string-escaping**: prefer **triple-quoted strings** (`"""…"""`) for SSR patterns — single backslashes are written verbatim, no doubling needed. In double-quoted strings (`"…"`) every backslash must be doubled: `"…java\\.util\\.Optional<.*>…"`. Counting backslashes in normal strings is the most error-prone part of authoring SSR patterns — when a pattern fails to match, check the runtime value of the string before assuming the SSR engine is broken.

### Expression vs statement patterns

A template's role (expression vs statement) is decided by the language's parser, not by SSR. Your trailing `;` is the cue:

| Trailing `;` | Pattern role | Matches | Common cases |
|---|---|---|---|
| No `;` | **expression** | Any expression context: `if (...)`, RHS of assignment, method argument, return value, ternary branch, etc. | Method calls used as values (`'_o.get()`), arithmetic (`'_a + '_b`), casts (`('_T) '_x`), null checks (`'_x == null`). |
| Trailing `;` | **statement** | A statement that produces this expression at top level | Statement-level method calls (`System.out.println('_x);`), assignments (`'_x = '_v;`), declarations (`int '_v = '_init;`). |
| `{ '_st*; }` | **block** | A block containing zero or more statements | Method bodies, control-flow bodies. Combined with class/method templates. |

Same expression in two roles produces two different patterns: `'_o.get()` (expression — matches a `get()` callsite anywhere) vs `'_o.get();` (statement — matches only when the callsite is a top-level statement, ignoring the return value). The expression form is almost always what you want for an audit.

Plus the escape hatch: any name starting with `_` is a custom macro routed to the language profile's predicate registry.

```
var '_p:[_AlsoMatchVal( ENABLED )] = '_init    # Kotlin: val pattern also matches var
```

## The implicit `__context__` variable

Every parsed template has a hidden variable named `__context__` representing the entire matched element. Two macros only work on it: `within(...)` and `context(...)`. Inside a `script(...)` filter you can also reference `__context__` by name.

To attach a constraint to `__context__` directly, start the template with a leading bracketed condition:

```
[within( "class '_C { '_M; }" )]
'_st;
```

This means "match a single statement, but only when the whole match is nested inside a class with at least one member". Same effect as setting `withinConstraint` programmatically on the constraint named `__context__`.

## PSI-tree mental model — proof from the live IDE

Run this in `steroid_execute_code` (apostrophe form):

```
val opts = MatchOptions().apply { setFileType(JavaFileType.INSTANCE) }
opts.fillSearchCriteria("class '_C extends '_Base:[regex( *Number )] {}")
println(opts.searchPattern)         // class $C$ extends $Base$ {}
println(opts.variableConstraintNames)  // [__context__, C, Base]

val compiled = PatternCompiler.compilePattern(project, opts, true, true)
val it = compiled.nodes
while (it.hasNext()) { dumpPsi(it.current()); it.advance() }
```

The PSI dump shows a real `PsiClassImpl` containing a `PsiReferenceListImpl` for `extends $Base$` whose child `PsiJavaCodeReferenceElementImpl` holds a `PsiIdentifier` with text `__$_Base`. The variable is just a Java identifier with the platform-internal prefix glued on; the constraint (`regex` + `withinHierarchy=true` from the leading `*`) lives separately on the `MatchVariableConstraint` and is evaluated by the predicate after the structural shape matches.

So when you write a template, ask yourself: "if I replaced `'_x` with the literal identifier `x`, would this still parse as the target language?" If yes, the structural shape is correct. The constraints are decoration on top.

**Corollary — literal identifiers anchor exactly.** Only names introduced by the apostrophe (`'_x`, `'_method`, `'_args`) become matching variables. Every other identifier in the template is a literal that the matcher requires verbatim. So `System.out.'_m('_args*);` matches every method called on `System.out` (because `System` and `out` are literals) but NOT `System.err.anything(...)` — to match either receiver, write `System.'_io.'_m('_args*);` and constrain `'_io` with `:[regex( out|err )]`.

**Worked example — chained calls.** `runCatching { '_BODY* }.onFailure { '_E -> '_HANDLER* }` has THREE literal anchors: `runCatching`, `.`, and `onFailure`. So this template matches `runCatching { … }.onFailure { … }` but does NOT match `runCatching { … }.getOrElse { … }`, `runCatching { … }.getOrThrow()`, or `foo.runCatching { … }.onFailure { … }` (the leading receiver `foo` doesn't match the receiver-less template). To widen, replace each literal you want generalized with a typed variable: `'_x.runCatching { '_BODY* }.'_M( '_args* )` matches any chained call after `runCatching`, with any receiver.

**Worked example — qualified receivers.** `System.out.'_m('_args*);` has the literal anchors `System`, `.`, `out`, and `.`. The receiver `System.out` is matched character-for-character. Behaviour:

| Source code | Matches `System.out.'_m('_args*);`? | Why |
|---|---|---|
| `System.out.println("hi");` | yes | Receiver `System.out` matches; `.println` binds `'_m`; `("hi")` binds `'_args*`. |
| `System.out.printf("%d", 1);` | yes | Same anchors; `'_m` = `printf`, `'_args*` = two args. |
| `System.err.println("hi");` | **no** | Literal `out` is required; `err` is a different identifier. |
| `out.println("hi");` (where `out` is a parameter or local) | **no** | The receiver chain `System.out.…` is required; a single-identifier receiver doesn't match. |
| `someClass.out.println("hi");` | **no** | `System` is a literal anchor; the receiver chain must start with it. |

To widen the receiver, introduce a typed variable: `'_recv.'_field.'_m('_args*);` and constrain `'_recv` / `'_field` with `:[regex(...)]` or `:[exprtype(...)]` as needed.

**Trailing semicolon for Java statement patterns**: Java SSR templates parsed as statements must include the trailing `;`. So `System.out.println("x");` is a valid statement template; `System.out.println("x")` (no semicolon) is an expression and matches expression contexts only. Same rule for variable declarations, method calls used as statements, etc. If you omit the semicolon expecting it to "auto-close", the validator may pass but the matcher will silently match nothing — the same silent-zero-match trap as the missing `~` on `exprtype`.

**`{}` matches any body (including non-empty)**: a pattern like `class '_C implements '_I {}` matches classes with any body content — methods, fields, nested classes — because SSR's loose-matching default (`MatchOptions.looseMatching=true`) skips unmatched siblings. To constrain body content, write the relevant elements explicitly inside the braces (`class '_C { '_T '_field; '_M; }`), or use a `:[contains(...)]` constraint on the whole template, or a `:[script(...)]` filter. The same applies to `void '_m() {}` matching method bodies, `{ … }` matching block expressions in Kotlin, and similar.

**Java `implements` matches both `implements` and `extends`**: in a Java class template, `class '_C implements '_I {}` matches classes that implement an interface AND classes that extend a class. Java SSR collapses both reference-list contexts during matching — so a single pattern finds direct implementors, direct subclasses, and (with the `:*Type` hierarchy modifier) transitive ones too. You do not need separate `implements` and `extends` patterns. Reference: see use-case F1 in [use-cases](mcp-steroid://skill/structural-search-use-cases).

## "This variable is the target"

The first non-underscore-prefixed apostrophe variable becomes the search target — i.e. matches anchor on this variable's subtree, not the whole template.

```
'Method('_param*)        # 'Method (no leading _) is the target → results selected as method names
'_o.'Method('_param*)    # 'Method is the target — same shape but anchored to the call's name
```

Equivalently, set `MatchVariableConstraint.setPartOfSearchResults(true)` programmatically. Setting target on more than one variable per template raises `MalformedPatternException("error.only.one.target.allowed")`.

## Predefined templates

The IDE ships a per-language gallery (Existing Templates in the dialog cog menu). Programmatic discovery is preferred over a curated list — the gallery grows IDE-version by IDE-version:

```kotlin[AI,IC,IU]
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.structuralsearch.StructuralSearchUtil

val all = StructuralSearchUtil.getPredefinedTemplates()                      // all profiles, sorted, cached
val javaProfile = StructuralSearchUtil.getProfileByFileType(JavaFileType.INSTANCE)!!
val javaOnly  = javaProfile.predefinedTemplates                              // the Java set
javaOnly.forEach { println("${it.category}/${it.name}: ${it.matchOptions.searchPattern}") }
```

The Java profile shipped roughly 98 predefined templates in IntelliJ 2026.1; Kotlin K2 ~34, JavaScript ~13, Go ~21, XML ~10. These counts evolve with each IDE release — use them as order-of-magnitude expectations, not exact values, and always probe the live `predefinedTemplates.size` for the version you are targeting. Every predefined template is a `Configuration` with `MatchOptions` already populated — feed it directly to a `Matcher`.

## Cross-references

- [Canonical API recipe](mcp-steroid://skill/structural-search-api-recipe) — how to feed a template to `Matcher`/`Replacer` safely
- [Language coverage matrix](mcp-steroid://skill/structural-search-coverage) — which language profile to use per file type
- [Kotlin SSR specifics](mcp-steroid://skill/structural-search-kotlin) — the four custom `_AlsoMatch...` macros, async FQN shortening, the 12 known limitations
