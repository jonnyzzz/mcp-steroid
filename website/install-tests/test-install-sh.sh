#!/bin/sh
# In-container driver for the install.sh end-to-end Docker test.
#
# Run by InstallerBootstrapTest (test-integration) as:
#   docker run --rm -v <repo>/website:/website:ro ubuntu:24.04 \
#     sh /website/install-tests/test-install-sh.sh
#
# Modeled on jonnyzzz/devrig's cli/bootstrap/test-with-docker-sandbox.sh:
# the repo is mounted read-only, the script runs against a fresh HOME whose
# path deliberately contains a space (to catch quoting bugs), and the
# container has NO Java — proving the installer's bundled-JDK path works
# end-to-end. Prints INSTALL_SH_E2E_OK on success; any failure exits non-zero.
set -eu

log() { printf '%s\n' "[driver] $*" >&2; }
die() { log "FATAL: $*"; exit 1; }

export DEBIAN_FRONTEND=noninteractive
log "installing prerequisites (curl, unzip, ca-certificates) — NO Java"
apt-get update -q >/dev/null
apt-get install -y -q curl unzip ca-certificates >/dev/null

if command -v java >/dev/null 2>&1; then
  die "the container has java on PATH — the no-host-JDK assertion would be meaningless"
fi

# Fresh HOME with a space in the path to catch quoting bugs (devrig pattern).
HOME="/home/test user"
export HOME
mkdir -p "$HOME"
unset JAVA_HOME DEVRIG_JAVA_HOME 2>/dev/null || true

log "running install.sh (first run — real downloads)"
sh /website/static/install.sh

[ -x "$HOME/.mcp-steroid/bin/devrig" ] || die "bin/devrig missing or not executable"
ls "$HOME/.mcp-steroid/binaries" | grep -q '^devrig-linux-' || die "no devrig-linux-* tree under binaries/"
ls "$HOME/.mcp-steroid/binaries" | grep -q '^jdk-linux-' || die "no jdk-linux-* tree under binaries/"

log "running 'devrig version' WITHOUT any host JDK"
version_out=$("$HOME/.mcp-steroid/bin/devrig" version 2>&1) || {
  printf '%s\n' "$version_out" >&2
  die "devrig version failed"
}
printf '%s\n' "$version_out" >&2
printf '%s' "$version_out" | grep -q '[0-9][0-9.]*' || die "devrig version printed no version number"

log "re-running install.sh (must converge, skipping both big downloads)"
second=$(sh /website/static/install.sh 2>&1) || {
  printf '%s\n' "$second" >&2
  die "second install.sh run failed"
}
printf '%s\n' "$second" >&2
skip_count=$(printf '%s\n' "$second" | grep -c 'already installed') || true
[ "$skip_count" -eq 2 ] || die "expected exactly 2 'already installed' lines (devrig + JDK) on re-run, got $skip_count"

# Stale-staging hygiene: a converged install leaves no .tmp.* entries behind.
leftovers=$(ls -A "$HOME/.mcp-steroid/binaries" | grep -c '^\.tmp\.') || true
[ "$leftovers" -eq 0 ] || die "found $leftovers stale .tmp.* staging entries after converged install"

echo "INSTALL_SH_E2E_OK"
