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
#   - ALL progress output goes to stderr; stdout stays untouched.
#   - Idempotent: re-running converges. Download to temp + atomic move;
#     an already-installed sha is trusted and not re-downloaded.
#   - The installer places files only — no lock files, no markers, no state.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Log([string]$Message) {
    [Console]::Error.WriteLine("[mcp-steroid] $Message")
}
function Fail([string]$Message) {
    Write-Log "ERROR: $Message"
    exit 1
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
# plugin). Match the devrig asset only.
$asset = $release.assets | Where-Object { $_.name -like 'devrig-*.zip' } | Select-Object -First 1
if (-not $asset) { Fail 'no devrig-*.zip asset found in the latest release' }
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
            try {
                Move-Item -Path $tmpDir -Destination $target
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
$ps1Shim = Join-Path $BinDir 'devrig.ps1'
@(
    "& `"$launcher`" @args"
    'exit $LASTEXITCODE'
) | Set-Content -Path $ps1Shim -Encoding ascii

$cmdShim = Join-Path $BinDir 'devrig.cmd'
@(
    '@echo off'
    "call `"$launcher`" %*"
) | Set-Content -Path $cmdShim -Encoding ascii
Write-Log "launcher: $cmdShim -> $launcher"

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
    $versionLine = (& $javaBin -version 2>&1 | Select-Object -First 1) -join ''
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
