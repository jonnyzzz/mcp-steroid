## Release Automation Workspace

This directory contains release orchestration assets for `mcp-steroid`:

- `release-instructions.md` — primary prompt/instructions for a release worker agent.
- `TODO-release.md` — release backlog and completion checklist.
- `docker/Dockerfile` — reproducible builder image used for release builds/tests.
- `scripts/` — helper scripts for dry-run orchestration, version bump, and Dockerized build matrix execution.
- `prompts/` — prompt templates for release notes and website update agent runs.
- `notes/` — committed versioned release notes (`release/notes/<version>.md`) used by build/publish.
- `out/` — deterministic output directory for release artifacts.

### Repository Layout

- **Main repo** (`jonnyzzz/intellij-mcp-steroids`, private): plugin source, build, tests
- **Public repo** (`jonnyzzz/mcp-steroid`): GitHub releases, website, EULA, VERSION
- **Local**: `website/` directory is a clone of the public repo (gitignored from main)

Both repos have matching `VERSION` files and release tags.

### Quick Release (Shorter Path)

For a quick release without the full Docker build matrix, use Claude Code agents directly:

1. **Bump version**: Edit `VERSION` in both main repo root and `website/VERSION`, commit both.
2. **Collect release notes**: Write `release/notes/<version>.md`.
3. **Build**: `./gradlew clean :ij-plugin:buildPlugin :npx-kt:distZip -Pmcp.release.build=true -Pmcp.release.notes.version=<version> -x :test-integration:test`
   (builds the plugin zip **and** the devrig CLI zip `npx-kt/build/distributions/devrig-<version>-<gitHash>.zip` from the same commit)
4. **Publish to GitHub** (on the public repo `jonnyzzz/mcp-steroid`):
   ```bash
   # Create release targeting the public repo commit
   gh release create "v<version>" \
     ij-plugin/build/distributions/mcp-steroid-*.zip \
     npx-kt/build/distributions/devrig*-*.zip \
     EULA \
     --repo jonnyzzz/mcp-steroid \
     --target "$(git -C website rev-parse HEAD)" \
     --title "<version>" \
     --notes-file release/notes/<version>.md
   ```
   Glob caveat: `devrig*-*.zip` also matches stale local dev builds
   (`devrig-<version>.19999-SNAPSHOT-<hash>.zip`). Ensure a clean `build/distributions`
   (the `clean` in step 3 handles this) or name the release zip explicitly.
   **EULA handling**: The `gh` CLI uses the source filename as the asset name. The root `EULA` file is uploaded directly.
5. **Upload to JetBrains Marketplace**:
   ```bash
   release/scripts/publish-marketplace.sh ij-plugin/build/distributions/mcp-steroid-*.zip
   ```
   Requires `~/.marketplace` file containing the JetBrains Marketplace permanent token (one line).
6. **Tag both repos**:
   ```bash
   git tag -a "v<version>" -m "release: <version>" HEAD && git push origin "v<version>"
   cd website && git tag -a "v<version>" -m "release: <version>" HEAD && git push origin "v<version>"
   ```
7. **Update website homepage**: In `website/website/hugo.toml`, update `params.version` to the new version and add a `[[params.whatsnew]]` entry at the top. Commit and push in `website/`.
8. **Update website release page**: Create `website/website/content/releases/<version>.md`, run `cd website/website && make build`. **Note:** `make build` downloads the release ZIP to extract the plugin version, so step 4 must complete first.
9. **Mark older releases obsolete**: `gh release edit <old-version> --repo jonnyzzz/mcp-steroid --notes-file <updated-body-with-obsolete-banner>`.
10. **Publish website** — advance the `website` deploy branch (this is what GitHub Pages builds from; a push to `main` does NOT deploy):
    ```bash
    git checkout website && git merge main --ff-only && git push origin website && git checkout main
    ```
    The `website` branch is origin-only (never pushed to `jb`). See `release-instructions.md` →
    "The `website` deploy branch" + "Stage 7c" for the full rationale (it lets website changes that
    depend on an unreleased binary stay off the live site until the matching release exists).

Steps 2+3 can run in parallel. Steps 7–10 require step 4 (GitHub release must exist for website build). The `CLAUDECODE` env var must be unset for nested Claude Code invocations via `run-agent.sh`.

### Full Release (Docker Matrix)

Default execution mode is dry-run (`release/scripts/run-release.sh --dry-run`), which:

- Skips version changes (`VERSION` file remains unchanged)
- Disables publishing (no GitHub release created)
- Enforces clean worktree (override with `--allow-dirty`)
- Still runs build/test matrix and release notes preparation

Release notes workflow:

- Notes are generated/reviewed into `release/notes/<version>.md`
- Notes format should match GitHub release body (user-friendly prose, not raw commit hashes)
- In non-dry-run, orchestration commits this file
- Build injects this file into plugin `change-notes` metadata (plugin.xml patch)

Stable plugin artifact path: `release/out/plugin-idea-2025.3.1.zip`
devrig CLI artifact path: `release/out/devrig-<version>-<gitHash>.zip` (basename preserved from `:npx-kt:distZip`)

EAP build selection:

- `RELEASE_EAP_VERSION` defaults to `2026.1` (major request)
- The matrix resolves it to latest matching EAP build number before Stage 2/3 execution

Release build version format:

- `X.Y.Z-<gitHash>`
- No timestamp
- No `SNAPSHOT` marker

Publish stage available in non-dry-run mode with explicit `--publish` flag:

```bash
release/scripts/run-release.sh --no-dry-run --publish
```

Publish safety defaults:

- Uses tag `v<VERSION>` and targets the recorded version-bump commit SHA
- Refuses `--publish` together with `--skip-build` or `--skip-notes` unless `--allow-existing-artifacts` is passed
- Refuses to publish if the GitHub release tag already exists
- **Immutable releases**: once published on `jonnyzzz/mcp-steroid`, releases cannot be modified or have assets added. If you need to fix a release, delete it and recreate. Tags locked by immutable releases cannot be reused — use a different tag format (e.g. `v0.91.0` vs `0.91.0`).

Container builds use an isolated `.intellijPlatform` Docker volume to prevent host-OS IDE cache conflicts.

Builder container API key forwarding:

- Reuses host env vars when present: `OPENAI_API_KEY`, `CODEX_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `GOOGLE_API_KEY`
- If missing, auto-loads from known local files and forwards as env:
  - `~/.openai` -> `OPENAI_API_KEY` (and `CODEX_API_KEY` fallback)
  - `~/.anthropic` -> `ANTHROPIC_API_KEY`
  - `~/.vertes` / `~/.vertex` -> `GEMINI_API_KEY` (and `GOOGLE_API_KEY` fallback)

### Website & Plugin Repository

The `website/` directory is a clone of the public repo (`jonnyzzz/mcp-steroid`). The Hugo site sources live in `website/website/`.

The website build (`cd website/website && make build`) automatically generates:

- `version.json` — current version for in-IDE update checker
- `updatePlugins.xml` — IntelliJ custom plugin repository XML

The `updatePlugins.xml` generation pipeline:

1. Makefile queries `gh` for the release ZIP download URL, trying `v<VERSION>` tag first then `<VERSION>` (no fallback — build fails if `gh` unavailable or release not found)
2. `scripts/generate-update-plugins-xml.py` (run via `uv`) downloads the ZIP, extracts `ij-plugin-*.jar`, reads `META-INF/plugin.xml` to get the exact plugin version (e.g. `0.91.0-13655642`) and `since-build`
3. Validates URL format (HTTPS, GitHub release pattern, version present) and generates XML with the artifact's actual version

This ensures `updatePlugins.xml` always matches the published artifact. Always points to the latest release only.

Requirements: `gh` CLI (authenticated), `uv` (Python runner).

Custom plugin repository URL: `https://mcp-steroid.jonnyzzz.com/updatePlugins.xml`

### Post-Release Checklist

- [ ] VERSION bumped in both repos (main + website)
- [ ] Release tags created in both repos (`git tag -a "v<version>"`)
- [ ] GitHub release has both assets: plugin ZIP + EULA
- [ ] Plugin uploaded to JetBrains Marketplace (`release/scripts/publish-marketplace.sh`)
- [ ] Website homepage version and whatsnew updated (`hugo.toml`)
- [ ] Older GitHub releases marked obsolete (prepend banner pointing to latest)
- [ ] Website release pages for older versions auto-show obsolete banner (handled by `layouts/releases/single.html`)
- [ ] Releases list page shows latest prominently, older releases in separate section
- [ ] `updatePlugins.xml` points to the new release (verified by `make build`)
- [ ] Website published (`cd website && git add -A && git commit && git push`)
