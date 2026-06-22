# MCP Steroid Release Instructions

## Repository Layout

There is **one repository**: `jonnyzzz/mcp-steroid` (at `/Users/jonnyzzz/Work/mcp-steroid`).
It contains both the plugin source and the website source (under `website/`).
The website is built and deployed automatically by the **GitHub Actions** workflow
(`.github/workflows/github-pages.yml`) on every push to the **`website` branch** that touches
`website/**` or `VERSION`. There is no separate website repo and no need to commit generated files.

### The `website` deploy branch

The live site deploys from a long-lived **`website` branch**, NOT from `main`. `website` tracks
`main` (advance it by merging `main → website`, normally often) but can deliberately **lag** `main`
so website changes that depend on an **unreleased** devrig binary — e.g. a new `install.sh` /
`install.ps1` contract that calls a CLI command only present in the next release — stay OFF the live
site until a matching GitHub release exists. **A push to `main` no longer deploys the website; you
advance `website` to publish.** This is done as a release step, AFTER the GitHub release is published
(see "Stage 7c: Advance the `website` branch"), because the generated `install.sh` / `updatePlugins.xml`
resolve the just-published release.

The `website` branch is **origin-only** — `jb` runs TeamCity only and carries no GitHub Actions, so
`website` is never synced to `jb`.

> **One-time GitHub setup:** the GitHub Pages **environment** ("Settings → Environments →
> github-pages → Deployment branches") must allow the **`website`** branch. If it still lists only
> `main`, the deploy job is blocked with "… is not allowed to deploy to github-pages due to
> environment protection rules."

## Commit-Then-Build Principle

**Always commit and push all release material before building the plugin.**
The plugin ZIP name embeds the HEAD git hash at build time (e.g. `mcp-steroid-0.92.0-59b78976.zip`).
The GitHub release `--target` must point to the same commit. If you add commits after the build,
the release will point to a newer commit than the plugin ZIP — avoid this.

Correct order:
1. Commit all release material (notes, website page, `hugo.toml`, `VERSION`)
2. Push to `origin/main` and sync to `jb/main`
3. Build the plugin **and the devrig CLI zip** (one Gradle invocation, same git hash)
4. Create tags on both remotes
5. Create the GitHub release (attaches to existing tag) — plugin zip + devrig zip + EULA
6. Upload to JetBrains Marketplace
7. **Advance the `website` branch** (merge `main → website`, push `origin website`) — this is what
   deploys the website; then verify it's live

**Critical:** Steps 1-2 must complete before step 3. **The push to `main` in step 2 does NOT trigger a
website deploy** — the Pages workflow only fires on the `website` branch. The website is published in
step 7 by advancing `website` to `main`, which must happen AFTER the GitHub release exists (step 5) so
the build can resolve the released ZIP URL + the released devrig binary that the new `install.sh`
expects.

## Release Stages

### Stage 0: Preflight

1. Working tree must be clean (`git status` shows nothing). If there is an unrelated dangling
   change (e.g. a tweak to a previous release's artifacts), commit it as a standalone
   non-release commit *before* starting Stage 1 — don't let it ride along on the version-bump
   commit.
2. `gh auth status` is valid.
3. `VERSION` holds the base version. Both `X.Y.Z` (e.g. `0.95.0`) and the newer
   two-component `X.Y` (e.g. `0.100`) forms are valid; the tag is `v<VERSION>` and the
   release-notes file is `release/notes/<VERSION>.md`. Note `release/scripts/bump-version.sh`
   only understands `X.Y.Z` — for a two-component bump (e.g. `0.96` → `0.100`), edit `VERSION`
   and commit by hand.
4. `~/.marketplace` token file exists (one-line JetBrains permanent token).
5. **Plugin verifier sanity check across all supported major IntelliJ versions.**

   ```bash
   ./gradlew :ij-plugin:verifyPlugin
   ```

   The set of IDEs that are checked is the source-of-truth in
   `ij-plugin/build.gradle.kts` under `pluginVerification.ides { … }`. As of v0.94.0 it is:

   ```kotlin
   create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3") { useInstaller = true }
   create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1") { useInstaller = true }
   ```

   When a new major IntelliJ version goes stable, **add it here first**, run the verifier,
   then proceed with the release. Only the two gates in 5a and 5b block the release; 5c is
   informational.

   **5a. `Compatible` status.** The verifier prints one summary line per IDE:

   ```
   IU-<build>: Compatible. <N> usages of experimental API
   ```

   Every IDE entry must say `Compatible.` If any IDE prints `Compatibility problems`,
   investigate and fix before continuing.

   **5b. Internal-API usages: must be zero. This is the only hard gate.** The detailed
   report under `ij-plugin/build/reports/pluginVerifier/IU-<build>/` lists
   `Internal API usages` if any. Internal APIs (`@ApiStatus.Internal`) can be removed in any
   IntelliJ minor release without notice, so using them is a release blocker. If the
   verifier reports any internal-API usage on any supported IDE, replace it with a public
   alternative before continuing — **do not ship.** As of v0.94.0 the count is **0** on
   both 253 and 261.

   **5c. Experimental-API usages: acceptable — not a gate.** Experimental APIs
   (`@ApiStatus.Experimental`) are part of IntelliJ's public surface; we are allowed to use
   them. The verifier line `IU-<build>: Compatible. <N> usages of experimental API` is
   informational. Do not block a release on the count and do not refactor working code just
   to drive it down.

   For reference (current call sites in v0.94.0):

   | # | API | Call site |
   |---|---|---|
   | 1 | `com.intellij.ide.plugins.IdeaPluginDescriptorExtensions` (class) | `ScriptClassLoader.kt:47` (`descriptor.contentModules`) |
   | 2 | `IdeaPluginDescriptorExtensions.getContentModules(IdeaPluginDescriptor)` (method) | same line — Kotlin emits one usage for the receiver type, one for the method invocation |
   | 3 | `com.intellij.openapi.application.CoroutinesKt.writeAction(Function0, Continuation)` (method) | `McpScriptContextImpl.kt:360` (`override suspend fun writeAction`) |

   Both call sites are public replacements adopted in 0.93.0 for previously-internal APIs.
   The release notes mention the count for transparency; nothing more is required.

### Stage 1: Review Changes and Collect Contributors

Before writing release notes, review all changes since the last release and identify
external contributors who should be acknowledged.

**1a. List commits since the last release tag:**
```bash
git log v<prev-version>..HEAD --oneline
```

**1b. Find external contributors (non-maintainer commits and co-authors):**
```bash
# Direct commit authors
git log v<prev-version>..HEAD --format='%an <%ae>' | sort -u

# Co-authors from commit bodies
git log v<prev-version>..HEAD --format='%b' | grep -i 'co-authored-by' | sort -u

# PRs merged in this range
gh pr list --repo jonnyzzz/mcp-steroid --state merged --search "merged:>$(git log -1 --format=%ci v<prev-version> | cut -d' ' -f1)" --json author,title,number
```

**1c. Update `CONTRIBUTORS.md`** with any new contributors — add their name,
GitHub handle, and a short description of their contribution(s). Keep the list
alphabetically sorted within the Contributors section.

**1d. Mention contributors in both release notes and website release page** — list
contributor names with GitHub handles. No need to describe what they did — just thank
them by name.

**1e. Review and collect fixed issues:**

```bash
# List all issues (open and closed)
gh issue list --repo jonnyzzz/mcp-steroid --state all --limit 50 --json number,title,state,closedAt

# Find the v<prev-version> tag date
git log -1 --format=%ci v<prev-version>
```

For each closed issue with `closedAt` after the previous release tag date,
**plus** every still-open issue whose described fix actually landed in this
release range (these are easy to miss — check the body and the matching commits):

1. Verify the fix is actually in this release (check commits/PRs).
2. Add to the website release page under a **Fixed Issues** section:
   `- [#N](https://github.com/jonnyzzz/mcp-steroid/issues/N) — short description`.
3. **Defer the issue comment + close to Stage 9b** (after the release page is live)
   so the "Fixed in v<version>" comment can link to the published release page
   (`https://mcp-steroid.jonnyzzz.com/releases/<version>/`) and the GitHub release
   URL — both of which only exist after Stages 7b and 9. Skip the comment only if a
   developer has already mentioned the fix version on the issue.

### Stage 2: Release Notes

Create `release/notes/<version>.md` with user-facing prose (no raw commit hashes).
Follow the style of previous notes files. Include segues to any relevant blog posts.

If external contributors participated in this release, add a **Contributors** section:
```markdown
### Contributors

Thank you to the contributors in this release:
- **Name** ([@handle](https://github.com/handle))
```

Commit it:
```bash
git add release/notes/<version>.md CONTRIBUTORS.md
git commit -m "release: add <version> release notes and update contributors"
```

### Stage 3: Version Bump

```bash
release/scripts/bump-version.sh
```

This bumps `VERSION` (`X.Y.Z` → `X.(Y+1).0`), commits it, and writes a guard file
to `release/state/version-bump.env` (prevents double-bump on rerun).

Or manually:
```bash
echo "0.92.0" > VERSION
git add VERSION && git commit -m "release: bump version to 0.92.0"
```

### Stage 4: Website Release Page + Homepage Update

**4a. Release page** — create `website/content/releases/<version>.md` following the
pattern of `0.93.0.md`. Page structure (in this order):
1. Success banner + release date
2. **Highlights** (first — this is what users came to see)
3. Download (custom repo, marketplace, manual ZIP link)
4. Connecting to agents + supported agents
5. **Fixed Issues** (list of `#N — description` with links to GitHub issues)
6. **Contributors** (list names with GitHub handles — no descriptions needed)
7. Reporting issues + Discord
8. Feedback + support

EULA link must point to the GitHub release asset:
`https://github.com/jonnyzzz/mcp-steroid/releases/download/v<version>/EULA`

**3b. Homepage** — in `website/hugo.toml`:
- Update `params.version` to the new version
- Add a `[[params.whatsnew]]` entry at the top with date and summary

**Note**: `static/updatePlugins.xml` and `static/version.json` are generated by the
GitHub Actions CI (`make build`). Do **not** commit them — they are in `.gitignore`.
The CI rebuilds them automatically on every push.

Commit:
```bash
git add -f website/content/releases/<version>.md website/hugo.toml
git commit -m "release: add <version> website release page and update homepage version"
```

(The `-f` flag is needed because `website/` is in the root `.gitignore`, but existing
tracked files under `website/` still work normally. New files need `-f` once.)

### Stage 5: Push to Origin and Sync to JB Remote

```bash
git push origin main
```

All release commits (notes, version bump, website page, hugo.toml) must be pushed
**before** building the plugin and creating the GitHub release.

**This push does NOT trigger a website deploy** — the Pages workflow fires only on the `website`
branch, not `main`. The website is published later, in Stage 7c, by advancing `website` to `main`
(after the GitHub release exists). So there is no "expected failing Pages build" on the `main` push
anymore.

Then sync to the `jb` remote (TeamCity pulls from `jb/main`):
```bash
git fetch jb
git checkout -b jb-merge jb/main
git merge main --no-ff -m "Merge remote-tracking branch 'origin/main' into jb-merge"
git push jb jb-merge:main
git checkout main
git branch -D jb-merge
```

If there are conflicts (e.g. `github-pages.yml` deleted on jb), resolve them
appropriately — jb doesn't use GitHub Pages, so accept jb's deletion.

### Stage 6: Build Plugin and devrig CLI

```bash
./gradlew :ij-plugin:buildPlugin :npx-kt:distZip -Pmcp.release.build=true
```

Or via IntelliJ MCP (`steroid_execute_code`) with a Gradle run configuration.

The resulting plugin ZIP is in `ij-plugin/build/distributions/mcp-steroid-<version>-<gitHash>.zip`.
The devrig CLI ZIP is in `npx-kt/build/distributions/devrig-<version>-<gitHash>.zip`.
The `<gitHash>` must match the current HEAD (the version bump commit) for both — building
them in one Gradle invocation guarantees the same hash.

**devrig zip naming.** `:npx-kt:distZip` sets only `archiveBaseName = "devrig"`, so the
release archive is Gradle's default `devrig-<version>-<gitHash>.zip`, matching the plugin
zip's `mcp-steroid-<version>-<gitHash>.zip` convention. Local/dev builds (without
`-Pmcp.release.build=true`) carry the SNAPSHOT counter:
`devrig-<version>.19999-SNAPSHOT-<gitHash>.zip`. Only the non-SNAPSHOT name is a release
artifact; ignore SNAPSHOT zips left over from dev builds.

### Stage 7: Create Tags and GitHub Release

**7a. Create tags on both remotes:**

The release tag on `jb` must point to the **jb merge commit** (not the origin commit),
because TeamCity builds from `jb/main`. The tag on `origin` points to the build commit.

```bash
# Tag on origin (the build commit)
git tag "v<version>" "<gitHash>" -m "Release <version>"
git push origin "v<version>"

# Tag on jb (the merge commit that contains the build commit)
# Find the jb merge commit — it's the latest commit on jb/main after the sync in Stage 5
JB_MERGE=$(git ls-remote jb refs/heads/main | cut -f1)
git tag "v<version>" "$JB_MERGE" -f -m "Release <version>"
git push jb "v<version>"
```

**Note**: If `git push origin "v<version>"` says "already exists", the tag was created
correctly. If `gh release create` fails with "target_commitish is invalid", it means
the tag already exists on the remote — drop `--target` from the `gh` command.

**7b. Create the GitHub release:**

```bash
gh release create "v<version>" \
  "ij-plugin/build/distributions/mcp-steroid-<version>-<gitHash>.zip" \
  "npx-kt/build/distributions/devrig-<version>-<gitHash>.zip" \
  EULA \
  --repo jonnyzzz/mcp-steroid \
  --notes-file "release/notes/<version>.md" \
  --title "v<version>"
```

**Do NOT use `--target`** when the tag already exists on the remote — `gh` will reject
it with HTTP 422. The release attaches to the existing tag automatically.

**Assets**: The `gh` CLI uses each source filename as the asset name, so the release page
carries `mcp-steroid-<version>-<gitHash>.zip` (plugin), `devrig-<version>-<gitHash>.zip`
(devrig CLI), and `EULA`.

**EULA**: The root `EULA` file is uploaded directly. The `gh` CLI uses the source
filename as the asset name — it appears as `EULA` on the release page.

**Immutable**: Once created, releases cannot have assets added. If a fix is needed,
delete and recreate the release.

**7c. Advance the `website` branch (this publishes the website):**

The live site deploys from the `website` branch, and the generated `install.sh` / `install.ps1` /
`updatePlugins.xml` resolve the **just-published** release (Stage 7b) — its plugin ZIP URL and the
devrig binary whose CLI contract the new install scripts expect. So advance `website` to `main`
**only now**, after the release exists:

```bash
git fetch origin
git checkout website
git merge main --ff-only        # website normally trails main by exactly the held commits
git push origin website          # ← this push is what triggers the GitHub Pages deploy
git checkout main
```

If `--ff-only` is rejected (website has diverged), use a normal merge:
`git merge main -m "website: advance to <version> release"`. The push to `origin website` triggers
the Pages workflow; the deploy + verification happen in Stage 9.

> `website` is **origin-only** — do NOT push it to `jb` (jb has no GitHub Actions).

### Stage 8: Upload to JetBrains Marketplace

```bash
release/scripts/publish-marketplace.sh "ij-plugin/build/distributions/mcp-steroid-<version>-<gitHash>.zip"
```

Requires `~/.marketplace` (one-line JetBrains permanent token).

**Channel**: Uploads to the `Stable` channel (case-sensitive — `Stable`, not `stable` or `default`).

Plugin page: https://plugins.jetbrains.com/plugin/30019-mcp-steroid

The plugin enters the JetBrains review queue and will be listed once approved.

### Stage 9: Verify the Website is Live

The website deploy was triggered by the `git push origin website` in **Stage 7c**. The build
(`make build`) queries the GitHub release for the plugin ZIP URL + the released devrig binary that
the generated `install.sh` expects — both exist by now (Stage 7b).

You may see **one harmless prior run**: the `release: published` event from Stage 7b's
`gh release create` triggers a build from the **tag ref**, whose `deploy` job is blocked by the Pages
environment ("Deployment branches" allows `website`, not tag refs) — error `Tag "v<version>" is not
allowed to deploy to github-pages due to environment protection rules.` The `build` job usually
succeeds; only `deploy` is blocked. Do not weaken the environment rule — the `website`-branch push
from Stage 7c is what actually deploys.

If the Stage 7c push didn't fire a run (e.g. it was a no-op fast-forward with no `website/**` change),
trigger it explicitly against the **`website`** branch:

```bash
gh workflow run "Deploy to GitHub Pages" --repo jonnyzzz/mcp-steroid --ref website
```

**Monitor the deployment:**

```bash
# Wait a few seconds for the run to appear, then watch it
gh run list --repo jonnyzzz/mcp-steroid --workflow "Deploy to GitHub Pages" --limit 3
gh run watch <RUN_ID> --repo jonnyzzz/mcp-steroid
```

**Verify the website is live** (with cache-busting to avoid Cloudflare stale responses):

```bash
# version.json should show the new version
curl -sH "Cache-Control: no-cache" \
  "https://mcp-steroid.jonnyzzz.com/version.json?_=$(date +%s)"

# updatePlugins.xml should reference the new ZIP
curl -sH "Cache-Control: no-cache" \
  "https://mcp-steroid.jonnyzzz.com/updatePlugins.xml?_=$(date +%s)" | head -5

# Release page should return HTTP 200
curl -sI "https://mcp-steroid.jonnyzzz.com/releases/<version>/?_=$(date +%s)" | head -3
```

**Cloudflare caching:** The website is behind Cloudflare. Query-string cache-busting
(`?_=<timestamp>`) bypasses the edge cache. If you see stale content without the
query string, it will expire within Cloudflare's TTL (typically 2-4 hours for HTML,
shorter for JSON). Do not purge Cloudflare manually — wait or use cache-busting URLs
for verification.

**If verification fails:** Check the workflow run logs for errors. The most common
failure is the release ZIP not being found — ensure the GitHub release (Stage 7b)
completed successfully before the website build ran.

### Stage 9b: Close Fixed Issues

For each issue identified in Stage 1e (whether closed before this release or still
open with the fix in this release range), close it now with a comment that links to
the live release page and the GitHub release. Both URLs only exist after Stages 7b
and 9, which is why this step lives here, not in Stage 1.

```bash
gh issue close <N> --repo jonnyzzz/mcp-steroid --comment "Fixed in v<version> — <one-line summary of the fix>.

Release notes: https://mcp-steroid.jonnyzzz.com/releases/<version>/"
```

If a developer has already mentioned the fix version on the issue, skip the comment.

### Stage 10: Mark Older Releases Obsolete (Optional)

The website template automatically renders an obsolete banner on older release pages
(handled by `layouts/releases/single.html` — no manual content changes needed).

## Key Paths

| File | Purpose |
|------|---------|
| `CONTRIBUTORS.md` | Contributor acknowledgements (update each release) |
| `VERSION` | Current plugin version (`X.Y.Z`) |
| `EULA` | End User License Agreement (uploaded to GitHub releases) |
| `npx-kt/build/distributions/devrig-<version>-<gitHash>.zip` | devrig CLI archive (`:npx-kt:distZip`); uploaded to GitHub releases |
| `release/notes/<version>.md` | Release notes (used as GitHub release body) |
| `release/scripts/bump-version.sh` | Version bump with rerun guard |
| `release/scripts/publish-marketplace.sh` | JetBrains Marketplace upload (Stable channel) |
| `website/hugo.toml` | Homepage version + whatsnew entries |
| `website/content/releases/<version>.md` | Website release page |
| `website/static/updatePlugins.xml` | **Generated by CI** — do not commit |
| `website/static/version.json` | **Generated by CI** — do not commit |
| `.github/workflows/github-pages.yml` | Automated website build + deploy |

## Notes

- Release notes format: user-friendly prose with section headers, no raw commit hashes.
- All links in release pages must use full URLs (`https://...`) — release content is also
  shown on GitHub where relative links break.
- Custom plugin repository: `https://mcp-steroid.jonnyzzz.com/updatePlugins.xml`
- The `updatePlugins.xml` is generated by `website/scripts/generate-update-plugins-xml.py`
  (run via `uv`). It downloads the release ZIP, extracts `plugin.xml` from the JAR to get
  the exact version and `since-build`. No fallbacks — build fails on any error.
