#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
#
# Release-readiness gate for Stage 7c (release/release-instructions.md).
#
# Advancing the `website` branch regenerates and publishes version.json + install.sh + install.ps1,
# and a live version.json STARTS THE FLEET-WIDE devrig AUTO-UPDATE ROLLOUT — every `devrig mcp`
# session that sees the new version downloads and runs the install script
# (docs/updates-check/devrig-auto-update.md). So the website may only be advanced once the GitHub
# release is completely ready. This script fails (non-zero) unless:
#   - release v<VERSION> exists and is neither a draft nor a prerelease;
#   - it carries all three release assets: mcp-steroid-<VERSION>.0-r-<hash>.zip,
#     devrig-<VERSION>.0-r-<hash>.zip (the #360 release-lane naming), and EULA;
#   - the devrig asset is a real release artifact (no SNAPSHOT in the name).
#
# The website build itself is the mechanical backstop (:website-gen / :installer-gen fail hard when
# the release or an asset is missing, so a premature push fails the Pages build and the OLD site
# stays live) — this gate exists so the failure is caught BEFORE the push, not after.
#
# Usage: release/scripts/verify-release-ready.sh [version]   (defaults to the VERSION file)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${1:-$(tr -d '[:space:]' < "$ROOT_DIR/VERSION")}"
REPO="jonnyzzz/mcp-steroid"
TAG="v$VERSION"

fail() { echo "NOT READY: $*" >&2; exit 1; }

echo "Checking release $TAG on $REPO for version '$VERSION'..."

gh release view "$TAG" --repo "$REPO" --json tagName --jq '.tagName' >/dev/null 2>&1 \
  || fail "release $TAG does not exist — create it first (Stage 7b)"

is_draft="$(gh release view "$TAG" --repo "$REPO" --json isDraft --jq '.isDraft')"
[[ "$is_draft" == "false" ]] || fail "release $TAG is still a draft"

is_prerelease="$(gh release view "$TAG" --repo "$REPO" --json isPrerelease --jq '.isPrerelease')"
[[ "$is_prerelease" == "false" ]] || fail "release $TAG is marked as a prerelease"

assets="$(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name')"

# Release-lane artifact names (#360): <name>-<VERSION>.0-r-<hash>.zip. Escape the dots in VERSION.
VERSION_RE="$(printf '%s' "$VERSION" | sed 's/\./\\./g')"

plugin_asset="$(printf '%s\n' "$assets" | grep -E "^mcp-steroid-${VERSION_RE}\.0-r-[0-9a-f]+\.zip$" || true)"
[[ -n "$plugin_asset" ]] || fail "no plugin asset mcp-steroid-${VERSION}.0-r-<hash>.zip on $TAG (assets: $(printf '%s ' $assets))"

devrig_asset="$(printf '%s\n' "$assets" | grep -E "^devrig-${VERSION_RE}\.0-r-[0-9a-f]+\.zip$" || true)"
[[ -n "$devrig_asset" ]] || fail "no devrig asset devrig-${VERSION}.0-r-<hash>.zip on $TAG (assets: $(printf '%s ' $assets))"
case "$devrig_asset" in
  *SNAPSHOT*) fail "devrig asset '$devrig_asset' is a SNAPSHOT dev build, not a release artifact" ;;
esac

printf '%s\n' "$assets" | grep -qx "EULA" || fail "EULA asset missing on $TAG"

echo "READY: release $TAG is published with:"
echo "  plugin: $plugin_asset"
echo "  devrig: $devrig_asset"
echo "  EULA:   present"
echo "Safe to advance the website branch (Stage 7c) — this publishes version.json and starts the auto-update rollout."
