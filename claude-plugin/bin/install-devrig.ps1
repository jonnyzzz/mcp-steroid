# MCP Steroid -- one-time devrig installer wrapper (Windows).
# Run by the /mcp-steroid:setup slash command. Delegates to the canonical, signed
# installer; we deliberately do NOT re-implement platform detection, checksum, or
# JDK download (all owned by :installer-gen + the devrig binary).
# All diagnostic output goes to stderr

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# TODO: same as for sh script, probably should put it in some config later
$InstallUrl = 'https://mcp-steroid.jonnyzzz.com/install.ps1'

# Resolve the user home the same way the canonical installer does.
$InstallRoot = $env:USERPROFILE
if (-not $InstallRoot) { $InstallRoot = $env:HOME }
if (-not $InstallRoot) { $InstallRoot = [Environment]::GetFolderPath('UserProfile') }
$Devrig = Join-Path $InstallRoot '.mcp-steroid\bin\devrig.cmd'

[Console]::Error.WriteLine("mcp-steroid: installing devrig via $InstallUrl")
Invoke-RestMethod -Uri $InstallUrl | Invoke-Expression

# The canonical installer is idempotent; verify it actually produced the launcher so a
# truncated download or interrupted run fails loudly instead of looking successful.
if (-not (Test-Path $Devrig)) {
  [Console]::Error.WriteLine("mcp-steroid: install finished but devrig was not found at $Devrig")
  [Console]::Error.WriteLine("mcp-steroid: the install may have been interrupted -- run /mcp-steroid:setup again")
  exit 1
}

[Console]::Error.WriteLine("mcp-steroid: devrig installed at $Devrig")
[Console]::Error.WriteLine("mcp-steroid: restart Claude so the mcp-steroid MCP server can start")
