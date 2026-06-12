# devrig bootstrap installer for Windows — https://mcp-steroid.jonnyzzz.com/install.ps1
#
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://mcp-steroid.jonnyzzz.com/install.ps1 | iex"
#
# Downloads the latest devrig release of jonnyzzz/mcp-steroid, verifies its
# SHA-256 against the GitHub release asset digest, unpacks it verbatim under
# the %USERPROFILE%\.mcp-steroid\ layout from docs/devrig-deployment-spec.md,
# and writes launcher shims into %USERPROFILE%\.mcp-steroid\bin.
#
# Layout (spec v7, "Filesystem layout"):
#   ~\.mcp-steroid\bin\devrig.ps1 + devrig.cmd        <- launcher shims (minimal v1)
#   ~\.mcp-steroid\binaries\devrig-windows-<cpu>-<sha>\ <- unpacked release zip, verbatim
#
# Discipline:
#   - Targets Windows PowerShell 5.1 AND pwsh 7+.
#   - ALL progress output goes to stderr; stdout stays untouched.
#   - Idempotent: re-running converges. Download to temp + atomic move; a
#     lost install race is detected and cleaned up; an already-installed
#     sha is trusted and not re-downloaded.
#   - The installer places files only — no lock files, no markers, no state.
#   - Errors `throw` (never `exit`): under a bare `irm ... | iex` pasted into
#     an interactive session, `exit` would close the user's terminal window.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# Windows PowerShell 5.1 on older .NET Framework (pre-4.7 defaults, unpatched
# Win 8.1 / Server 2012) may still negotiate TLS 1.0, which api.github.com
# rejects with a generic "Could not create SSL/TLS secure channel". Opt into
# TLS 1.2 — but leave SystemDefault (0) alone: overwriting it would DISABLE
# newer protocols (TLS 1.3) the OS already negotiates. SystemDefault is
# checked numerically (0) on purpose: that enum member does not exist on
# pre-4.7 frameworks (the very boxes this guard is for) and referencing
# [Net.SecurityProtocolType]::SystemDefault there throws. Tls12 (3072) has
# existed since .NET 4.5, so the named member is safe.
$currentTls = [int][Net.ServicePointManager]::SecurityProtocol
if ($currentTls -ne 0 -and -not ($currentTls -band 3072)) {
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

function Write-Log([string]$Message) {
    [Console]::Error.WriteLine("[mcp-steroid] $Message")
}
function Fail([string]$Message) {
    Write-Log "ERROR: $Message"
    # throw, not `exit`: iex runs this script in the CALLER's runspace, so
    # `exit` would kill an interactive session. The documented one-liner
    # (child powershell -Command) still exits non-zero on a thrown error.
    throw "[mcp-steroid] ERROR: $Message"
}

$Repo = 'jonnyzzz/mcp-steroid'
$SteroidHome = Join-Path $env:USERPROFILE '.mcp-steroid'
$BinDir = Join-Path $SteroidHome 'bin'
$BinariesDir = Join-Path $SteroidHome 'binaries'

# ─── platform detection ──────────────────────────────────────────────────────
$cpu = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'X64'   { 'x86_64' }
    'Arm64' { 'arm64' }
    default { Fail "unsupported CPU architecture '$_'" }
}
if ($env:DEVRIG_CPU) { $cpu = $env:DEVRIG_CPU }
Write-Log "platform: windows-$cpu"

# ─── resolve the latest devrig release asset ─────────────────────────────────
Write-Log "resolving latest release of $Repo..."
try {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" `
        -Headers @{ 'User-Agent' = 'mcp-steroid-install' }
} catch {
    Fail "cannot reach the GitHub API to resolve the latest release: $_"
}

# The release carries both devrig-*.zip (the CLI) and mcp-steroid-*.zip (the
# plugin). Match the devrig asset only. The release ships a single universal
# devrig zip, yet the install dir below is named devrig-windows-<cpu>-<sha>;
# if per-platform zips ever appear, taking "the first match" would silently
# install whichever sorts first — fail loudly instead so this installer
# grows an os/cpu asset filter.
$assets = @($release.assets | Where-Object { $_.name -like 'devrig-*.zip' })
if ($assets.Count -eq 0) { Fail 'no devrig-*.zip asset found in the latest release' }
if ($assets.Count -gt 1) {
    Fail "expected exactly one devrig-*.zip asset, found $($assets.Count) — this installer needs an os/cpu asset filter now"
}
$asset = $assets[0]
Write-Log "latest devrig asset: $($asset.name)"

$assetSha = $null
if ($asset.digest -and $asset.digest -like 'sha256:*') {
    $assetSha = $asset.digest.Substring(7).ToLowerInvariant()
} else {
    Write-Log 'WARNING: the release publishes no asset digest; integrity cannot be verified'
}

# ─── content-addressed target (spec: binaries\devrig-<os>-<cpu>-<sha>\) ──────
$target = $null
if ($assetSha) {
    $target = Join-Path $BinariesDir "devrig-windows-$cpu-$assetSha"
    if (Test-Path $target -PathType Container) {
        Write-Log "already installed: $(Split-Path $target -Leaf) (skipping download)"
    }
}

New-Item -ItemType Directory -Force -Path $BinDir, $BinariesDir | Out-Null

# Runs killed before their finally block fires (process kill, power loss)
# orphan .tmp.* staging entries that no later run owns ($PID differs every
# run). Sweep anything older than a day — live installs are younger.
try {
    Get-ChildItem -Path $BinariesDir -Force |
        Where-Object { $_.Name -like '.tmp.*' -and $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
        ForEach-Object { Remove-Item -Recurse -Force -LiteralPath $_.FullName }
} catch {
    Write-Log "WARNING: could not sweep stale .tmp.* staging entries: $_"
}

if (-not $target -or -not (Test-Path $target -PathType Container)) {
    # Download into binaries\ so the final move stays on one volume (atomic).
    $tmpZip = Join-Path $BinariesDir ".tmp.$PID.download.zip"
    $tmpDir = Join-Path $BinariesDir ".tmp.$PID.unpack"
    try {
        Write-Log "downloading $($asset.name) (~225 MB)..."
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tmpZip `
            -Headers @{ 'User-Agent' = 'mcp-steroid-install' }

        $actualSha = (Get-FileHash -Path $tmpZip -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($assetSha) {
            if ($actualSha -ne $assetSha) {
                Fail "SHA-256 mismatch for $($asset.name): expected $assetSha, got $actualSha"
            }
            Write-Log "SHA-256 verified: $assetSha"
        } else {
            # No published digest: still content-address the directory by the
            # local hash (naming only — nothing to verify against).
            $target = Join-Path $BinariesDir "devrig-windows-$cpu-$actualSha"
        }

        if (Test-Path $target -PathType Container) {
            Write-Log "already installed: $(Split-Path $target -Leaf) (skipping unpack)"
        } else {
            Write-Log "unpacking verbatim into $(Split-Path $target -Leaf)..."
            if (Test-Path $tmpDir) { Remove-Item -Recurse -Force $tmpDir }
            Expand-Archive -Path $tmpZip -DestinationPath $tmpDir
            # Move-Item has two success modes here:
            #   - $target absent: atomic rename — we won the race. The target
            #     only ever appears fully populated.
            #   - $target already a directory (another install finished
            #     first): Move-Item does NOT throw — it nests $tmpDir INSIDE
            #     the winner's tree. Detect the nested path and remove the
            #     duplicate; the winner's tree is complete because it too
            #     only appeared via atomic rename.
            try {
                Move-Item -Path $tmpDir -Destination $target
                $nested = Join-Path $target (Split-Path $tmpDir -Leaf)
                if (Test-Path $nested -PathType Container) {
                    Write-Log 'another install finished first; using the existing tree'
                    Remove-Item -Recurse -Force $nested
                }
            } catch {
                if (Test-Path $target -PathType Container) {
                    Write-Log 'another install finished first; using the existing tree'
                    Remove-Item -Recurse -Force $tmpDir
                } else {
                    throw
                }
            }
        }
    } finally {
        if (Test-Path $tmpZip) { Remove-Item -Force $tmpZip }
        if (Test-Path $tmpDir) { Remove-Item -Recurse -Force $tmpDir }
    }
}

# ─── locate the launcher inside the verbatim tree ────────────────────────────
# The zip contains a single top-level devrig-<version>\ directory with the
# launcher at bin\devrig.bat (the spec manifest's binSubpath for Windows).
$launcher = Get-ChildItem -Path $target -Directory |
    ForEach-Object { Join-Path $_.FullName 'bin\devrig.bat' } |
    Where-Object { Test-Path $_ -PathType Leaf } |
    Select-Object -First 1
if (-not $launcher) { Fail "launcher bin\devrig.bat not found inside $target" }

# ─── write launcher shims into bin\ (spec wrapper replaces these in v7) ──────
# Shims forward args verbatim and write nothing to stdout themselves, so the
# MCP stdio channel stays clean when an agent runs `devrig mcp` through them.
#
# The launcher path is embedded RELATIVE to %USERPROFILE% so the shim text
# stays pure ASCII even when the Windows username contains non-ASCII
# characters (Cyrillic/CJK/accented names are common) — an absolute path
# under `Set-Content -Encoding ascii` would silently turn those characters
# into '?', producing shims that point at a nonexistent path. Everything
# below the profile dir (.mcp-steroid\binaries\devrig-...-<sha>\...) is
# ASCII by construction; the guard fails loudly if that ever changes.
$profileRoot = $env:USERPROFILE.TrimEnd('\')
if (-not $launcher.StartsWith($profileRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    Fail "launcher '$launcher' is not under USERPROFILE '$profileRoot'"
}
$launcherRel = $launcher.Substring($profileRoot.Length).TrimStart('\')
if ($launcherRel -match '[^\x20-\x7E]' -or $launcherRel.Contains("'") -or $launcherRel.Contains('"')) {
    Fail "launcher relative path is not plain ASCII: '$launcherRel' — cannot write a safe shim"
}

$ps1Shim = Join-Path $BinDir 'devrig.ps1'
@(
    "& (Join-Path `$env:USERPROFILE '$launcherRel') @args"
    'exit $LASTEXITCODE'
) | Set-Content -Path $ps1Shim -Encoding ascii

$cmdShim = Join-Path $BinDir 'devrig.cmd'
@(
    '@echo off'
    "call `"%USERPROFILE%\$launcherRel`" %*"
) | Set-Content -Path $cmdShim -Encoding ascii
Write-Log "launcher: $cmdShim -> %USERPROFILE%\$launcherRel"

# ─── Java 25 check (devrig does not bundle a JVM) ────────────────────────────
$javaBin = $null
if ($env:DEVRIG_JAVA_HOME -and (Test-Path (Join-Path $env:DEVRIG_JAVA_HOME 'bin\java.exe'))) {
    $javaBin = Join-Path $env:DEVRIG_JAVA_HOME 'bin\java.exe'
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaBin = Join-Path $env:JAVA_HOME 'bin\java.exe'
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
    $javaBin = 'java'
}
if (-not $javaBin) {
    Write-Log 'WARNING: no Java found. devrig requires Java 25 — install a JDK 25'
    Write-Log '         (e.g. Amazon Corretto 25) or set DEVRIG_JAVA_HOME / JAVA_HOME.'
} else {
    # `java -version` writes everything to STDERR. In Windows PowerShell 5.1
    # a `2>&1` redirect of native stderr under $ErrorActionPreference='Stop'
    # turns the first stderr line into a terminating NativeCommandError —
    # the installer would die here for users who DO have Java. Relax EAP
    # around the probe and stringify the ErrorRecord-wrapped lines.
    $versionLine = ''
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $versionLine = (& $javaBin -version 2>&1 | ForEach-Object { "$_" } | Select-Object -First 1)
    } catch {
        Write-Log "WARNING: failed to run '$javaBin -version': $_"
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($versionLine -match '"(\d+)') {
        $javaMajor = [int]$Matches[1]
        if ($javaMajor -lt 25) {
            Write-Log "WARNING: found Java $javaMajor, but devrig requires Java 25."
            Write-Log '         Install a JDK 25 or point DEVRIG_JAVA_HOME at one.'
        } else {
            Write-Log "Java $javaMajor found: OK"
        }
    } else {
        Write-Log "WARNING: could not determine the Java version of '$javaBin'; devrig requires Java 25"
    }
}

# ─── PATH hint ────────────────────────────────────────────────────────────────
$onPath = ($env:Path -split ';') | Where-Object { $_.TrimEnd('\') -eq $BinDir }
if ($onPath) {
    Write-Log "$BinDir is already on your PATH"
} else {
    Write-Log ''
    Write-Log "$BinDir is not on your PATH. Add it for the current user:"
    Write-Log ''
    Write-Log "    [Environment]::SetEnvironmentVariable('Path', `"$BinDir;`" + [Environment]::GetEnvironmentVariable('Path', 'User'), 'User')"
    Write-Log ''
    Write-Log '    (then restart your terminal)'
    Write-Log ''
}

Write-Log 'devrig installed successfully.'
Write-Log ''
Write-Log 'Next step — register devrig with your coding agent:'
Write-Log ''
Write-Log '    devrig install claude'
Write-Log ''
