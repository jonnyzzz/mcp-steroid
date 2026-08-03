# devrig -- one-time installer wrapper (Windows).
# Run by the /devrig:setup slash command. Delegates to the canonical, signed
# installer; we deliberately do NOT re-implement platform detection, checksum, or
# JDK download (all owned by :installer-gen + the devrig binary).
# All diagnostic output goes to stderr

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# TODO: same as for sh script, probably should put it in some config later
$InstallUrl = 'https://devrig.dev/install.ps1'

# Resolve the user home the same way the canonical installer does.
$InstallRoot = $env:USERPROFILE
if (-not $InstallRoot) { $InstallRoot = $env:HOME }
if (-not $InstallRoot) { $InstallRoot = [Environment]::GetFolderPath('UserProfile') }
$Devrig = Join-Path $InstallRoot '.mcp-steroid\bin\devrig.cmd'
$MarkerDir = Join-Path $InstallRoot '.mcp-steroid\markers'
$Failed = Join-Path $MarkerDir 'bootstrap-install.failed'

# Records why the install failed so check-devrig (SessionStart hook) can surface it and
# devrig_status can report the detail.
function Write-FailedMarker([string]$Reason) {
  New-Item -ItemType Directory -Force -Path $MarkerDir | Out-Null
  Set-Content -Path $Failed -Value $Reason -ErrorAction SilentlyContinue
  [Console]::Error.WriteLine("devrig: $Reason")
}

[Console]::Error.WriteLine("devrig: installing devrig via $InstallUrl")
try {
  Invoke-RestMethod -Uri $InstallUrl | Invoke-Expression
} catch {
  Write-FailedMarker("install command failed: $_")
  exit 1
}

# The canonical installer is idempotent; verify it actually produced the launcher so a
# truncated download or interrupted run fails loudly instead of looking successful.
if (-not (Test-Path $Devrig)) {
  Write-FailedMarker("install finished but devrig was not found at $Devrig (the install may have been interrupted -- run /devrig:setup again)")
  exit 1
}

# Success -- clear any prior failure marker so check-devrig stops warning about it.
if (Test-Path $Failed) { Remove-Item -Path $Failed -ErrorAction SilentlyContinue }

[Console]::Error.WriteLine("devrig: devrig installed at $Devrig")
[Console]::Error.WriteLine("devrig: restart Claude so the devrig MCP server can start")
