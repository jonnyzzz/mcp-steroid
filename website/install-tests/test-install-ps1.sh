#!/bin/sh
# In-container launcher for the install.ps1 Docker test (pwsh on Linux).
#
# Run by InstallerBootstrapTest (test-integration) as:
#   docker run --rm -v <repo>/website:/website:ro mcr.microsoft.com/dotnet/sdk:8.0 \
#     sh /website/install-tests/test-install-ps1.sh
#
# The dotnet SDK image ships pwsh — the same PowerShell-on-Linux trick
# jonnyzzz/devrig's bootstrap suite uses to cover its .ps1 script without a
# Windows runner. USERPROFILE is stubbed to a fresh Linux home (with a space
# in the path, to catch quoting bugs); the actual assertions live in
# test-install-ps1.ps1.
set -eu

log() { printf '%s\n' "[driver] $*" >&2; }

if command -v java >/dev/null 2>&1; then
  log "FATAL: the container has java on PATH — the no-host-JDK assertion would be meaningless"
  exit 1
fi

USERPROFILE="/home/test user"
export USERPROFILE
mkdir -p "$USERPROFILE"

exec pwsh -NoProfile -File /website/install-tests/test-install-ps1.ps1
