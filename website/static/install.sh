#!/bin/sh
# devrig bootstrap installer — https://mcp-steroid.jonnyzzz.com/install.sh
#
# Usage:  curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh | sh
#
# Downloads the latest devrig release of jonnyzzz/mcp-steroid (verified
# against the GitHub release asset digest) PLUS a matching Amazon Corretto 25
# JDK (verified against Corretto's published SHA-256), unpacks both verbatim
# under the ~/.mcp-steroid/ layout from docs/devrig-deployment-spec.md, and
# writes a launcher wrapper into ~/.mcp-steroid/bin/devrig that exports
# JAVA_HOME from the bundled JDK — no preinstalled Java is required.
#
# Layout (spec v7, "Filesystem layout"):
#   ~/.mcp-steroid/bin/devrig                       <- launcher wrapper (sets JAVA_HOME, execs the launcher)
#   ~/.mcp-steroid/binaries/devrig-<os>-<cpu>-<sha>/ <- unpacked release zip, verbatim (no strip)
#   ~/.mcp-steroid/binaries/jdk-<os>-<cpu>-<sha>/    <- unpacked Corretto 25 JDK, verbatim
#
# Discipline:
#   - POSIX sh only, set -eu.
#   - ALL output goes to stderr; stdout stays untouched (MCP stdio rule).
#   - Idempotent: re-running converges. Download to temp + atomic mv; a lost
#     install race is detected and cleaned up; an already-installed sha is
#     trusted and not re-downloaded.
#   - The installer places files only — no lock files, no markers, no state.
#   - The whole body runs through main(), invoked on the last line, so a
#     truncated `curl | sh` transfer can never execute a partial script.

set -eu

REPO="jonnyzzz/mcp-steroid"
STEROID_HOME="$HOME/.mcp-steroid"
BIN_DIR="$STEROID_HOME/bin"
BINARIES_DIR="$STEROID_HOME/binaries"

log() { printf '%s\n' "[mcp-steroid] $*" >&2; }
fail() { log "ERROR: $*"; exit 1; }

# promote_tree <staging-dir> <target-dir>
# Atomically promotes an unpacked staging tree into its content-addressed
# target directory; tolerates a concurrent install winning the race.
#
# POSIX mv has two success modes here:
#   - target absent: rename(2), atomic — we won the race. The target only
#     ever appears fully populated (that is why we do NOT pre-claim it with
#     the spec's mkdir-lock: an empty claimed dir would be visible to
#     concurrent installs as "already installed").
#   - target already a directory (another install finished first): mv does
#     NOT fail — it moves the staging dir INSIDE the winner's tree. Detect
#     that nested path and remove the duplicate; the winner's tree is
#     complete because it too only appeared via atomic rename.
promote_tree() {
  if mv "$1" "$2" 2>/dev/null; then
    promoted_nested="$2/${1##*/}"
    if [ -d "$promoted_nested" ]; then
      log "another install finished first; using the existing tree"
      rm -rf "$promoted_nested"
    fi
  elif [ -d "$2" ]; then
    # Some mv implementations fail instead of nesting — same outcome.
    log "another install finished first; using the existing tree"
    rm -rf "$1"
  else
    fail "could not move unpacked tree into place: $2"
  fi
}

main() {

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
# plugin). Match the devrig asset only.
#
# NOTE: this parser depends on GitHub's pretty-printed serialization order —
# "name" preceding "digest" and "browser_download_url" within each asset
# object. That holds today but is not contractually guaranteed; if GitHub
# ever reorders the fields, the failure below is loud (no asset found),
# never a silently wrong download.
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

# The release ships a single universal devrig zip, yet the install dir below
# is named devrig-<os>-<cpu>-<sha>. If per-platform zips ever appear, taking
# "the first match" would silently install whichever sorts first on every
# platform — fail loudly instead so this installer grows an os/cpu filter.
asset_count=$(printf '%s\n' "$asset_info" | awk '$1 == "url" { n++ } END { print n + 0 }')
[ "$asset_count" -gt 0 ] || fail "no devrig-*.zip asset found in the latest release"
[ "$asset_count" -eq 1 ] \
  || fail "expected exactly one devrig-*.zip asset, found ${asset_count} — this installer needs an os/cpu asset filter now"

asset_url=$(printf '%s\n' "$asset_info" | awk '$1 == "url" { print $2; exit }')
asset_sha=$(printf '%s\n' "$asset_info" | awk '$1 == "sha256" { print $2; exit }')
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

# Runs killed before their EXIT trap fires (SIGKILL, power loss, closed
# terminal) orphan .tmp.* staging entries that no later run owns ($$ differs
# every run). Sweep anything older than a day — live installs are younger.
find "$BINARIES_DIR" -maxdepth 1 -name '.tmp.*' -mtime +0 -exec rm -rf {} + \
  || log "WARNING: could not sweep stale .tmp.* staging entries from ${BINARIES_DIR}"

if [ -z "$target" ] || [ ! -d "$target" ]; then
  command -v unzip >/dev/null 2>&1 || fail "unzip not found — install it and re-run"
  # A SHA-256 tool is mandatory: it both verifies the download and names the
  # content-addressed install dir. Without it the directory name would be
  # non-deterministic, so every re-run would pile up a fresh ~500 MB tree
  # that no future GC keep-set could ever match. Fail hard — no silent,
  # unverifiable fallback install.
  [ -n "$sha_verify_tool" ] \
    || fail "neither shasum nor sha256sum found — cannot verify or content-address the download; install one and re-run"

  # Download into binaries/ so the final mv stays on one filesystem (atomic).
  tmp_zip="$BINARIES_DIR/.tmp.$$.download.zip"
  tmp_dir="$BINARIES_DIR/.tmp.$$.unpack"
  trap 'rm -rf "$release_json" "$tmp_zip" "$tmp_dir"' EXIT

  log "downloading ${asset_name} (~225 MB)..."
  fetch "$asset_url" "$tmp_zip" || fail "download failed: ${asset_url}"

  actual_sha=$($sha_verify_tool "$tmp_zip" | awk '{ print $1 }')
  if [ -n "$asset_sha" ]; then
    [ "$actual_sha" = "$asset_sha" ] \
      || fail "SHA-256 mismatch for ${asset_name}: expected ${asset_sha}, got ${actual_sha}"
    log "SHA-256 verified: ${asset_sha}"
  else
    # No published digest: still content-address the directory by the local
    # hash (naming only — nothing to verify against; warned above).
    target="$BINARIES_DIR/devrig-${os}-${cpu}-${actual_sha}"
  fi

  if [ -d "$target" ]; then
    log "already installed: ${target##*/} (skipping unpack)"
  else
    log "unpacking verbatim into ${target##*/}..."
    rm -rf "$tmp_dir"
    mkdir -p "$tmp_dir"
    unzip -q "$tmp_zip" -d "$tmp_dir"
    promote_tree "$tmp_dir" "$target"
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

# ─── bundled JDK (Amazon Corretto 25; devrig requires Java 25) ──────────────
# devrig must run without a preinstalled Java. Resolve the matching Amazon
# Corretto 25 JDK from the official "latest" permalink, verify it against
# Corretto's published SHA-256, and unpack it verbatim into the spec layout
# binaries/jdk-<os>-<cpu>-<sha>/. The wrapper written below exports JAVA_HOME
# from this tree on every launch; DEVRIG_JAVA_HOME (honored by the launcher
# itself) still overrides the bundled JDK.
JDK_MAJOR=25
case "$os" in
  darwin) jdk_os=macos ;;
  linux)  jdk_os=linux ;;
  *) fail "no Corretto ${JDK_MAJOR} platform mapping for OS '${os}'" ;;
esac
case "$cpu" in
  arm64)  jdk_cpu=aarch64 ;;
  x86_64) jdk_cpu=x64 ;;
  *) fail "no Corretto ${JDK_MAJOR} platform mapping for CPU '${cpu}'" ;;
esac
jdk_asset="amazon-corretto-${JDK_MAJOR}-${jdk_cpu}-${jdk_os}-jdk.tar.gz"
jdk_dl="https://corretto.aws/downloads"

# The checksum is fetched first (a few bytes): it both verifies the download
# and content-addresses the install dir, so an already-installed JDK is
# detected without re-downloading the ~200 MB archive.
log "resolving Amazon Corretto ${JDK_MAJOR} for ${jdk_os}-${jdk_cpu}..."
jdk_sha_file="$BINARIES_DIR/.tmp.$$.jdk.sha256"
trap 'rm -rf "$release_json" "$jdk_sha_file"' EXIT
fetch "${jdk_dl}/latest_sha256/${jdk_asset}" "$jdk_sha_file" \
  || fail "cannot fetch the Corretto checksum: ${jdk_dl}/latest_sha256/${jdk_asset}"
jdk_sha=$(tr -d '[:space:]' < "$jdk_sha_file" | tr 'A-F' 'a-f')
rm -f "$jdk_sha_file"
[ "${#jdk_sha}" -eq 64 ] || fail "unexpected Corretto checksum '${jdk_sha}' for ${jdk_asset}"
case "$jdk_sha" in
  *[!0-9a-f]*) fail "unexpected Corretto checksum '${jdk_sha}' for ${jdk_asset}" ;;
esac

jdk_target="$BINARIES_DIR/jdk-${os}-${cpu}-${jdk_sha}"
if [ -d "$jdk_target" ]; then
  log "JDK already installed: ${jdk_target##*/} (skipping download)"
else
  command -v tar >/dev/null 2>&1 || fail "tar not found — install it and re-run"
  [ -n "$sha_verify_tool" ] \
    || fail "neither shasum nor sha256sum found — cannot verify the JDK download; install one and re-run"

  jdk_tmp_tar="$BINARIES_DIR/.tmp.$$.jdk.tar.gz"
  jdk_tmp_dir="$BINARIES_DIR/.tmp.$$.jdk.unpack"
  trap 'rm -rf "$release_json" "$jdk_sha_file" "$jdk_tmp_tar" "$jdk_tmp_dir"' EXIT

  log "downloading ${jdk_asset} (~200 MB)..."
  fetch "${jdk_dl}/latest/${jdk_asset}" "$jdk_tmp_tar" || fail "download failed: ${jdk_dl}/latest/${jdk_asset}"

  actual_jdk_sha=$($sha_verify_tool "$jdk_tmp_tar" | awk '{ print $1 }')
  [ "$actual_jdk_sha" = "$jdk_sha" ] \
    || fail "SHA-256 mismatch for ${jdk_asset}: expected ${jdk_sha}, got ${actual_jdk_sha}"
  log "SHA-256 verified: ${jdk_sha}"

  log "unpacking verbatim into ${jdk_target##*/}..."
  rm -rf "$jdk_tmp_dir"
  mkdir -p "$jdk_tmp_dir"
  tar -xzf "$jdk_tmp_tar" -C "$jdk_tmp_dir"
  promote_tree "$jdk_tmp_dir" "$jdk_target"
  rm -f "$jdk_tmp_tar"
fi

# Locate JAVA_HOME inside the verbatim tree. macOS Corretto unpacks to
# amazon-corretto-<N>.jdk/Contents/Home; Linux to a single top-level
# amazon-corretto-<version>-linux-<cpu>/ directory.
jdk_home=""
for jdk_candidate in "$jdk_target"/*/Contents/Home "$jdk_target"/* "$jdk_target"; do
  if [ -x "$jdk_candidate/bin/java" ]; then
    jdk_home="$jdk_candidate"
    break
  fi
done
[ -n "$jdk_home" ] || fail "bin/java not found inside ${jdk_target}"

# ─── write the launcher wrapper into bin/ (spec entry point) ────────────────
# The wrapper embeds both paths RELATIVE to $HOME and exports JAVA_HOME from
# the bundled JDK before exec-ing the real launcher, so the user needs no
# preinstalled Java. It writes nothing to stdout — stdout belongs to the MCP
# stdio channel when an agent runs `devrig mcp` through it.
launcher_rel=${launcher#"$HOME"/}
jdk_home_rel=${jdk_home#"$HOME"/}
case "$launcher_rel" in /*) fail "launcher '$launcher' is not under HOME '$HOME'" ;; esac
case "$jdk_home_rel" in /*) fail "JDK home '$jdk_home' is not under HOME '$HOME'" ;; esac

tmp_wrapper="$BIN_DIR/.tmp.$$.devrig"
cat > "$tmp_wrapper" <<EOF
#!/bin/sh
# devrig launcher wrapper — written by install.sh; re-run the installer to refresh.
# Exports JAVA_HOME from the bundled Corretto JDK so no preinstalled Java is
# needed; DEVRIG_JAVA_HOME (read by the launcher itself) still overrides it.
# Writes nothing to stdout — stdout is the MCP stdio channel.
if [ -z "\${DEVRIG_JAVA_HOME:-}" ] && [ -x "\$HOME/${jdk_home_rel}/bin/java" ]; then
  JAVA_HOME="\$HOME/${jdk_home_rel}"
  export JAVA_HOME
fi
exec "\$HOME/${launcher_rel}" "\$@"
EOF
chmod +x "$tmp_wrapper"
# mv (rename) replaces bin/devrig atomically — and replaces a pre-existing v1
# SYMLINK itself rather than following it into the trusted binaries/ tree (a
# write-through like `cat >` would clobber the cached launcher there).
mv -f "$tmp_wrapper" "$BIN_DIR/devrig"
log "launcher: $BIN_DIR/devrig -> \$HOME/${launcher_rel}"
log "JAVA_HOME (bundled): \$HOME/${jdk_home_rel}"

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

}

main "$@"
