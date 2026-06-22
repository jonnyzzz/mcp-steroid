# website-gen/CLAUDE.md

Guidance for the `:website-gen` module. **Instructions here override the root guide for this folder.**
Read the [root CLAUDE.md](../CLAUDE.md) too (project-wide rules).

## What this module is

`:website-gen` is **build-tooling**, not plugin runtime — no IntelliJ dependencies. One job:

- **Website artifacts** (`WebsiteArtifacts.kt`, task `generateWebsite`) — resolves the published GitHub
  release and writes `version.json` + `updatePlugins.xml` (the IntelliJ custom-plugin-repository XML)
  into `website/build/generated-static`, and copies `EULA` + `LICENSE` there too. The task knows all
  paths (VERSION + release notes from the project layout); CI/Makefile invoke it with no args. The plugin
  version + `since-build` come from the ACTUAL published artifact (the release ZIP's `ij-plugin-*.jar`
  `META-INF/plugin.xml`), never a literal.

**The website Make contract is a SINGLE task.** `make update-config` runs only `:website-gen:generateWebsite`,
and that task emits EVERY static file the site serves — into `website/build/generated-static` (a `build/`
dir, gitignored, merged by Hugo via its `staticDir` list). So `generateWebsite` **dependsOn
`:installer-gen:generateInstaller`** (which writes `install.sh` + `install.ps1` into the same dir) and
copies `EULA`/`LICENSE` itself. Adding a new generated static file = make `generateWebsite` depend on the
task that produces it (or copy it in `generateWebsite`); never add a second task to the Makefile, and
never write generated files into the tracked `website/static`.

## Dependencies

- The ONLY `project()` dependency is **`:installer-gen`** — the lower build-tooling module that owns the
  shared HTTP/cache infra. `WebsiteArtifacts` reuses its `KtorHttpFetcher` (so this module has no Ktor
  dependency of its own). `:installer-gen` has no IntelliJ deps, so `generateWebsite` still never builds
  the plugin side of the project — it stays fast.
- JDK detection / the installer-script generation used to live here; both moved to **`:installer-gen`**.

## Testing

- Tests are hermetic (the `Url*Fetcher` seam is injected). Run: `./gradlew :website-gen:test`.
- Covered by `ciBuildPluginTests` (guarded — see root `build.gradle.kts`).
- ZIP/XML use the JDK built-ins; `version.json`/GitHub-API JSON use kotlinx.serialization. When comparing
  generated `updatePlugins.xml` to the published file, mind the `standalone="no"` omission + trailing
  newline (see `WebsiteArtifactsTest`).
