# devrig — Claude Code plugin

Give Claude the whole JetBrains IDE: run code, debug, refactor, and inspect any
running IntelliJ-based IDE from Claude via the devrig bridge.

## Install (from the marketplace)

In Claude Code:

```
/plugin marketplace add jonnyzzz/mcp-steroid
/plugin install devrig@jonnyzzz
/devrig:setup
```

`/devrig:setup` installs the devrig binary and registers it with Claude.
**Restart Claude** afterwards, then check with `/devrig:status`.

## Run a locally built devrig (your code changes, not the release)

After editing the repo, build devrig and point Claude's stable launcher at your
build. Claude's MCP registration always targets `~/.mcp-steroid/bin/devrig(.cmd)`,
so you never re-register — `install devrig` just repoints that wrapper.

Run from the repo root (needs JDK 25):

```
./gradlew :npx-kt:installDist
```

Then repoint the launcher at the freshly built binary:

**macOS**
```
./npx-kt/build/install/devrig/bin/devrig install devrig
```

**Windows**
```
npx-kt\build\install\devrig\bin\devrig.bat install devrig
```

**Restart Claude.** `/devrig:status` now runs your local build. Repeat both steps
after each code change.

Revert to the released binary anytime by re-running the website installer:

```
curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh | sh    # macOS
irm https://mcp-steroid.jonnyzzz.com/install.ps1 | iex         # Windows
```
