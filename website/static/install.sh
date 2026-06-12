#!/bin/sh
# devrig bootstrap installer — https://mcp-steroid.jonnyzzz.com/install.sh
#
# Usage:  curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh | sh
#
# Downloads the latest devrig release of jonnyzzz/mcp-steroid, verifies its
# SHA-256 against the GitHub release asset digest, unpacks it verbatim under
# the ~/.mcp-steroid/ layout from docs/devrig-deployment-spec.md, and links
# the launcher into ~/.mcp-steroid/bin/devrig.
#
# Layout (spec v7, "Filesystem layout"):
#   ~/.mcp-steroid/bin/devrig                       <- launcher entry point (symlink in this minimal v1)
#   ~/.mcp-steroid/binaries/devrig-<os>-<cpu>-<sha>/ <- unpacked release zip, verbatim (no strip)
#
# Discipline:
#   - POSIX sh only, set -eu.
#   - ALL output goes to stderr; stdout stays untouched (MCP stdio rule).
#   - Idempotent: re-running converges. Download to temp + atomic mv;
#     an already-installed sha is trusted and not re-downloaded.
#   - The installer places files only — no lock files, no markers, no state.

set -eu

REPO="jonnyzzz/mcp-steroid"
STEROID_HOME="$HOME/.mcp-steroid"
BIN_DIR="$STEROID_HOME/bin"
BINARIES_DIR="$STEROID_HOME/binaries"

log() { printf '%s\n' "[mcp-steroid] $*" >&2; }
fail() { log "ERROR: $*"; exit 1; }

# An optional "install" argument is accepted for forward compatibility with
# the spec's `curl ... | sh -s install` form; anything else is rejected.
if [ "$#" -gt 0 ] && [ "${1}" != "install" ]; then
  fail "unknown argument '${1}' (this installer takes no arguments)"
fi

# ─── platform detection ─────────────────────────────────────────────────────
os=${DEVRIG_OS:-}
cpu=${DEVRIG_CPU:-}
if [ -z "$os" ]; then
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux)  os=linux ;;
    *) fail "unsupported OS '$(uname -s)' — on Windows use install.ps1" ;;
  esac
fi
if [ -z "$cpu" ]; then
  case "$(uname -m)" in
    arm64|aarch64)  cpu=arm64 ;;
    x86_64|amd64)   cpu=x86_64 ;;
    *) fail "unsupported CPU architecture '$(uname -m)'" ;;
  esac
fi
log "platform: ${os}-${cpu}"

# ─── download helper (curl preferred, wget fallback) ────────────────────────
if command -v curl >/dev/null 2>&1; then
  fetch() { curl -fsSL "$1" -o "$2"; }
elif command -v wget >/dev/null 2>&1; then
  fetch() { wget -q "$1" -O "$2"; }
else
  fail "neither curl nor wget found — install one and re-run"
fi

# ─── resolve the latest devrig release asset ────────────────────────────────
log "resolving latest release of ${REPO}..."
release_json=$(mktemp "${TMPDIR:-/tmp}/devrig-release.XXXXXX")
trap 'rm -f "$release_json"' EXIT
fetch "https://api.github.com/repos/${REPO}/releases/latest" "$release_json" \
  || fail "cannot reach the GitHub API to resolve the latest release"

# The release carries both devrig-*.zip (the CLI) and mcp-steroid-*.zip (the
# plugin). Match the devrig asset only. GitHub's pretty-printed JSON puts each
# field on its own line, with "name" before "digest" and
# "browser_download_url" inside every asset object.
asset_info=$(awk '
  /"name":[[:space:]]*"devrig-[^"]*\.zip"/ { hit = 1; next }
  hit && /"name":[[:space:]]*"/             { hit = 0 }
  hit && /"digest":[[:space:]]*"sha256:/ {
    line = $0
    sub(/^.*"digest":[[:space:]]*"sha256:/, "", line)
    sub(/".*$/, "", line)
    print "sha256 " line
  }
  hit && /"browser_download_url":[[:space:]]*"/ {
    line = $0
    sub(/^.*"browser_download_url":[[:space:]]*"/, "", line)
    sub(/".*$/, "", line)
    print "url " line
    hit = 0
  }
' "$release_json")

asset_url=$(printf '%s\n' "$asset_info" | awk '$1 == "url" { print $2; exit }')
asset_sha=$(printf '%s\n' "$asset_info" | awk '$1 == "sha256" { print $2; exit }')
[ -n "$asset_url" ] || fail "no devrig-*.zip asset found in the latest release"
asset_name=${asset_url##*/}
log "latest devrig asset: ${asset_name}"

# ─── content-addressed target (spec: binaries/devrig-<os>-<cpu>-<sha>/) ─────
# <sha> is the SHA-256 the GitHub release publishes for the asset. The
# manifest-driven wrapper of spec phase 1 will use the manifest's own hash;
# both are content hashes of the same archive family, so the GC keep-set
# logic and directory shape stay compatible.
sha_verify_tool=""
if command -v shasum >/dev/null 2>&1; then
  sha_verify_tool="shasum -a 256"
elif command -v sha256sum >/dev/null 2>&1; then
  sha_verify_tool="sha256sum"
fi

if [ -z "$asset_sha" ]; then
  log "WARNING: the release publishes no asset digest; integrity cannot be verified"
fi

target=""
if [ -n "$asset_sha" ]; then
  target="$BINARIES_DIR/devrig-${os}-${cpu}-${asset_sha}"
  if [ -d "$target" ]; then
    log "already installed: ${target##*/} (skipping download)"
  fi
fi

mkdir -p "$BIN_DIR" "$BINARIES_DIR"

if [ -z "$target" ] || [ ! -d "$target" ]; then
  command -v unzip >/dev/null 2>&1 || fail "unzip not found — install it and re-run"

  # Download into binaries/ so the final mv stays on one filesystem (atomic).
  tmp_zip="$BINARIES_DIR/.tmp.$$.download.zip"
  tmp_dir="$BINARIES_DIR/.tmp.$$.unpack"
  trap 'rm -rf "$release_json" "$tmp_zip" "$tmp_dir"' EXIT

  log "downloading ${asset_name} (~225 MB)..."
  fetch "$asset_url" "$tmp_zip" || fail "download failed: ${asset_url}"

  if [ -n "$asset_sha" ]; then
    if [ -n "$sha_verify_tool" ]; then
      actual_sha=$($sha_verify_tool "$tmp_zip" | awk '{ print $1 }')
      [ "$actual_sha" = "$asset_sha" ] \
        || fail "SHA-256 mismatch for ${asset_name}: expected ${asset_sha}, got ${actual_sha}"
      log "SHA-256 verified: ${asset_sha}"
    else
      log "WARNING: no shasum/sha256sum available — skipping integrity verification"
    fi
  fi

  if [ -z "$target" ]; then
    # No published digest: still content-address the directory by hashing
    # the archive locally (naming only — nothing to verify against).
    if [ -n "$sha_verify_tool" ]; then
      local_sha=$($sha_verify_tool "$tmp_zip" | awk '{ print $1 }')
    else
      local_sha="unverified-$(date +%Y%m%d%H%M%S)"
    fi
    target="$BINARIES_DIR/devrig-${os}-${cpu}-${local_sha}"
  fi

  if [ -d "$target" ]; then
    log "already installed: ${target##*/} (skipping unpack)"
  else
    log "unpacking verbatim into ${target##*/}..."
    rm -rf "$tmp_dir"
    mkdir -p "$tmp_dir"
    unzip -q "$tmp_zip" -d "$tmp_dir"
    if mv "$tmp_dir" "$target" 2>/dev/null; then
      : # we won the move
    elif [ -d "$target" ]; then
      log "another install finished first; using the existing tree"
      rm -rf "$tmp_dir"
    else
      fail "could not move unpacked tree into place: ${target}"
    fi
  fi
  rm -f "$tmp_zip"
fi

# ─── locate the launcher inside the verbatim tree ───────────────────────────
# The zip contains a single top-level devrig-<version>/ directory with the
# launcher at bin/devrig (the spec manifest's binSubpath).
launcher=""
for candidate in "$target"/*/bin/devrig; do
  if [ -f "$candidate" ]; then
    launcher="$candidate"
    break
  fi
done
[ -n "$launcher" ] || fail "launcher bin/devrig not found inside ${target}"
chmod +x "$launcher"

# ─── link into bin/ (spec entry point; wrapper script replaces this in v7) ──
ln -sfn "$launcher" "$BIN_DIR/devrig"
log "launcher: $BIN_DIR/devrig -> $launcher"

# ─── Java 25 check (devrig does not bundle a JVM) ───────────────────────────
java_bin=""
if [ -n "${DEVRIG_JAVA_HOME:-}" ] && [ -x "${DEVRIG_JAVA_HOME}/bin/java" ]; then
  java_bin="${DEVRIG_JAVA_HOME}/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  java_bin="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
  java_bin="java"
fi
if [ -z "$java_bin" ]; then
  log "WARNING: no Java found. devrig requires Java 25 — install a JDK 25"
  log "         (e.g. Amazon Corretto 25) or set DEVRIG_JAVA_HOME / JAVA_HOME."
else
  java_major=$("$java_bin" -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print v[1]; exit }')
  case "$java_major" in
    ''|*[!0-9]*)
      log "WARNING: could not determine the Java version of '$java_bin'; devrig requires Java 25" ;;
    *)
      if [ "$java_major" -lt 25 ]; then
        log "WARNING: found Java ${java_major}, but devrig requires Java 25."
        log "         Install a JDK 25 or point DEVRIG_JAVA_HOME at one."
      else
        log "Java ${java_major} found: OK"
      fi ;;
  esac
fi

# ─── PATH hint ───────────────────────────────────────────────────────────────
case ":${PATH}:" in
  *":$BIN_DIR:"*)
    log "$BIN_DIR is already on your PATH" ;;
  *)
    log ""
    log "$BIN_DIR is not on your PATH. Add it to your shell profile:"
    log ""
    log "    export PATH=\"\$HOME/.mcp-steroid/bin:\$PATH\""
    log "" ;;
esac

log "devrig installed successfully."
log ""
log "Next step — register devrig with your coding agent:"
log ""
log "    devrig install claude"
log ""
