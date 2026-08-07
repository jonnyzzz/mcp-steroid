#!/usr/bin/env bash
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

DRY_RUN="${RELEASE_DRY_RUN:-1}"
RUN_BUILD="1"
RUN_NOTES="1"
RUN_WEBSITE="0"
RUN_PUBLISH="0"
ALLOW_DIRTY="0"
ALLOW_EXISTING_ARTIFACTS="0"
RUN_AGENT_SCRIPT="${RUN_AGENT_SCRIPT:-/Users/jonnyzzz/Work/jonnyzzz-ai-coder/run-agent.sh}"
RELEASE_STABLE_PRODUCT="${RELEASE_STABLE_PRODUCT:-idea}"
RELEASE_STABLE_VERSION="${RELEASE_STABLE_VERSION:-2025.3.1}"
RELEASE_EAP_PRODUCT="${RELEASE_EAP_PRODUCT:-idea}"
RELEASE_EAP_VERSION="${RELEASE_EAP_VERSION:-2026.1}"
RELEASE_NOTES_FILE="${RELEASE_NOTES_FILE:-}"
RELEASE_ZIP_FILE="${RELEASE_ZIP_FILE:-$ROOT_DIR/release/out/plugin-${RELEASE_STABLE_PRODUCT}-${RELEASE_STABLE_VERSION}.zip}"
# devrig CLI zip is named `devrig-<version>-<gitHash>.zip` (Gradle distZip default,
# matching the plugin zip convention). The git hash is not known until build time, so
# resolve it by glob from release/out/ at publish time rather than hard-coding the name.
RELEASE_DEVRIG_ZIP_FILE="${RELEASE_DEVRIG_ZIP_FILE:-}"
RELEASE_TAG="${RELEASE_TAG:-}"
RELEASE_TARGET=""
RELEASE_NOTES_PREVIOUS_TAG=""
RELEASE_NOTES_COMMIT_RANGE=""
VERSION=""

normalize_bool() {
  local name="$1"
  local raw="$2"
  local normalized
  normalized="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$normalized" in
    1|true)
      printf '1\n'
      ;;
    0|false)
      printf '0\n'
      ;;
    *)
      echo "Unsupported $name value: '$raw' (expected true/false or 1/0)" >&2
      exit 2
      ;;
  esac
}

validate_version_file() {
  local version_file="$ROOT_DIR/VERSION"
  if [[ ! -f "$version_file" ]]; then
    echo "Missing VERSION file: $version_file" >&2
    exit 1
  fi
  local version
  version="$(tr -d '[:space:]' < "$version_file")"
  if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Unsupported VERSION format: '$version' (expected X.Y.Z)" >&2
    exit 1
  fi
  VERSION="$version"
}

check_tracked_clean_worktree() {
  if [[ "$ALLOW_DIRTY" == "1" ]]; then
    return 0
  fi

  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Unable to verify worktree cleanliness: not a git repository" >&2
    exit 1
  fi

  if ! git diff --quiet --ignore-submodules -- || ! git diff --cached --quiet --ignore-submodules --; then
    echo "Tracked files are not clean. Commit/stash tracked changes or rerun with --allow-dirty." >&2
    git status --short --untracked-files=no >&2 || true
    exit 1
  fi
}

check_publish_prerequisites() {
  if [[ "$RUN_PUBLISH" != "1" ]]; then
    return 0
  fi
  if [[ "$ALLOW_EXISTING_ARTIFACTS" != "1" && ( "$RUN_BUILD" != "1" || "$RUN_NOTES" != "1" ) ]]; then
    echo "Refusing publish with --skip-build/--skip-notes by default (risk of stale artifacts)." >&2
    echo "Run with --allow-existing-artifacts only if you intentionally publish prebuilt outputs." >&2
    exit 1
  fi
  if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI is required for publish stage" >&2
    exit 1
  fi
  if ! gh auth status >/dev/null 2>&1; then
    echo "gh is not authenticated; run 'gh auth login' before publish stage" >&2
    exit 1
  fi
}

resolve_release_notes_file() {
  if [[ -z "$RELEASE_NOTES_FILE" ]]; then
    RELEASE_NOTES_FILE="$ROOT_DIR/release/notes/$VERSION.md"
  fi
  mkdir -p "$(dirname "$RELEASE_NOTES_FILE")"
}

version_lt() {
  local left="$1"
  local right="$2"
  local smallest
  smallest="$(printf '%s\n%s\n' "$left" "$right" | sort -V | head -n1)"
  [[ "$left" == "$smallest" && "$left" != "$right" ]]
}

determine_release_notes_range() {
  local current_version="$VERSION"
  local tag
  local normalized_tag
  local tag_base
  local fallback_start

  RELEASE_NOTES_PREVIOUS_TAG=""
  while IFS= read -r tag; do
    normalized_tag="${tag#v}"
    normalized_tag="${normalized_tag#V}"
    if [[ ! "$normalized_tag" =~ ^([0-9]+\.[0-9]+\.[0-9]+) ]]; then
      continue
    fi
    tag_base="${BASH_REMATCH[1]}"
    if version_lt "$tag_base" "$current_version"; then
      RELEASE_NOTES_PREVIOUS_TAG="$tag"
    fi
  done < <(git tag --merged HEAD | sort -V)

  if [[ -n "$RELEASE_NOTES_PREVIOUS_TAG" ]]; then
    RELEASE_NOTES_COMMIT_RANGE="$RELEASE_NOTES_PREVIOUS_TAG..HEAD"
    return 0
  fi

  fallback_start="$(git rev-list --max-count=200 --reverse HEAD | head -n1)"
  if [[ -z "$fallback_start" ]]; then
    fallback_start="$(git rev-list --max-count=1 HEAD)"
  fi
  RELEASE_NOTES_COMMIT_RANGE="$fallback_start..HEAD"
}

check_agent_runner_prerequisites() {
  if [[ "$RUN_NOTES" != "1" && "$RUN_WEBSITE" != "1" ]]; then
    return 0
  fi
  if [[ ! -x "$RUN_AGENT_SCRIPT" ]]; then
    echo "Agent runner script is not executable: $RUN_AGENT_SCRIPT" >&2
    exit 1
  fi
}

load_release_target() {
  local state_file="$ROOT_DIR/release/state/version-bump.env"
  if [[ -f "$state_file" ]]; then
    # shellcheck disable=SC1090
    source "$state_file"
    if [[ -n "${COMMIT_SHA:-}" ]]; then
      RELEASE_TARGET="$COMMIT_SHA"
      return 0
    fi
  fi
  RELEASE_TARGET="$(git rev-parse HEAD)"
}

check_release_not_exists() {
  if [[ "$RUN_PUBLISH" != "1" ]]; then
    return 0
  fi
  if gh release view "$RELEASE_TAG" --repo jonnyzzz/mcp-steroid >/dev/null 2>&1; then
    echo "Release '$RELEASE_TAG' already exists in jonnyzzz/mcp-steroid." >&2
    echo "Refusing to overwrite. Use gh release edit/upload manually for recovery." >&2
    exit 1
  fi
}

run_release_notes_agents() {
  local collect_prompt_template="$ROOT_DIR/release/prompts/release-notes-collect.md"
  local review_prompt_template="$ROOT_DIR/release/prompts/release-notes-review.md"
  local collect_prompt
  local review_prompt

  collect_prompt="$(mktemp -t "release-notes-collect.${VERSION}.XXXXXX")"
  review_prompt="$(mktemp -t "release-notes-review.${VERSION}.XXXXXX")"

  cat > "$collect_prompt" <<EOF
Release context:
- target version: $VERSION
- release notes file: $RELEASE_NOTES_FILE
- repository root: $ROOT_DIR
- previous local ancestor tag: ${RELEASE_NOTES_PREVIOUS_TAG:-none}
- commit range to use exactly: $RELEASE_NOTES_COMMIT_RANGE

$(cat "$collect_prompt_template")
EOF

  cat > "$review_prompt" <<EOF
Release context:
- target version: $VERSION
- release notes file: $RELEASE_NOTES_FILE
- repository root: $ROOT_DIR
- previous local ancestor tag: ${RELEASE_NOTES_PREVIOUS_TAG:-none}
- commit range used: $RELEASE_NOTES_COMMIT_RANGE

$(cat "$review_prompt_template")
EOF

  "$RUN_AGENT_SCRIPT" codex \
    "$ROOT_DIR" \
    "$collect_prompt"

  "$RUN_AGENT_SCRIPT" codex \
    "$ROOT_DIR" \
    "$review_prompt"

  rm -f "$collect_prompt" "$review_prompt"
}

# GitHub renders release bodies in comment-style GFM: every single newline
# becomes <br>, so hard-wrapped notes show spurious mid-sentence line breaks
# on the release page (seen live on v0.101). Unwrap wrapped prose into single
# physical lines; code fences and markdown structure are preserved.
unwrap_release_notes() {
  local src="$1"
  local dst="$2"
  python3 "$ROOT_DIR/release/scripts/unwrap-release-notes.py" < "$src" > "$dst"
}

commit_release_notes_if_needed() {
  if [[ "$RUN_NOTES" != "1" ]]; then
    return 0
  fi
  if [[ ! -f "$RELEASE_NOTES_FILE" ]]; then
    echo "Release notes file was not created: $RELEASE_NOTES_FILE" >&2
    exit 1
  fi
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[DRY-RUN] Release notes commit skipped."
    return 0
  fi

  # Normalize before committing so the committed notes file is canonical.
  local unwrapped
  unwrapped="$(mktemp -t "release-notes-unwrap.${VERSION}.XXXXXX")"
  unwrap_release_notes "$RELEASE_NOTES_FILE" "$unwrapped"
  mv "$unwrapped" "$RELEASE_NOTES_FILE"

  git add -- "$RELEASE_NOTES_FILE"
  if git diff --cached --quiet -- "$RELEASE_NOTES_FILE"; then
    echo "Release notes unchanged, commit skipped."
    return 0
  fi

  git commit -m "release: add notes for $VERSION"
}

resolve_release_devrig_zip() {
  if [[ -n "$RELEASE_DEVRIG_ZIP_FILE" ]]; then
    return 0
  fi
  shopt -s nullglob
  local matches=()
  local f
  for f in "$ROOT_DIR"/release/out/devrig*.zip; do
    [[ "$f" == *SNAPSHOT* ]] && continue
    matches+=("$f")
  done
  shopt -u nullglob
  if [[ "${#matches[@]}" -eq 1 ]]; then
    RELEASE_DEVRIG_ZIP_FILE="${matches[0]}"
  elif [[ "${#matches[@]}" -gt 1 ]]; then
    echo "Multiple devrig ZIPs in release/out/; set RELEASE_DEVRIG_ZIP_FILE explicitly:" >&2
    printf '  %s\n' "${matches[@]}" >&2
    exit 1
  fi
}

validate_publish_inputs() {
  if [[ "$RUN_PUBLISH" != "1" ]]; then
    return 0
  fi
  if [[ ! -f "$RELEASE_NOTES_FILE" ]]; then
    echo "Missing release notes file for publish stage: $RELEASE_NOTES_FILE" >&2
    exit 1
  fi
  if [[ ! -f "$RELEASE_ZIP_FILE" ]]; then
    echo "Missing plugin ZIP for publish stage: $RELEASE_ZIP_FILE" >&2
    exit 1
  fi
  resolve_release_devrig_zip
  if [[ -z "$RELEASE_DEVRIG_ZIP_FILE" || ! -f "$RELEASE_DEVRIG_ZIP_FILE" ]]; then
    echo "Missing devrig CLI ZIP for publish stage: ${RELEASE_DEVRIG_ZIP_FILE:-<none in release/out/>}" >&2
    echo "Build it first (run-release-build-matrix.sh builds :npx-kt:distZip into release/out/)." >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN="1"
      shift
      ;;
    --no-dry-run)
      DRY_RUN="0"
      shift
      ;;
    --skip-build)
      RUN_BUILD="0"
      shift
      ;;
    --skip-notes)
      RUN_NOTES="0"
      shift
      ;;
    --website)
      RUN_WEBSITE="1"
      shift
      ;;
    --publish)
      RUN_PUBLISH="1"
      shift
      ;;
    --allow-dirty)
      ALLOW_DIRTY="1"
      shift
      ;;
    --allow-existing-artifacts)
      ALLOW_EXISTING_ARTIFACTS="1"
      shift
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Usage: $0 [--dry-run|--no-dry-run] [--skip-build] [--skip-notes] [--website] [--publish] [--allow-dirty] [--allow-existing-artifacts]" >&2
      exit 2
      ;;
  esac
done

DRY_RUN="$(normalize_bool "RELEASE_DRY_RUN" "$DRY_RUN")"

if [[ "$DRY_RUN" == "1" ]]; then
  if [[ "$RUN_PUBLISH" == "1" ]]; then
    echo "--publish is disabled in dry-run mode." >&2
  fi
  RUN_PUBLISH="0"
fi

validate_version_file
check_tracked_clean_worktree
check_agent_runner_prerequisites
check_publish_prerequisites

if [[ "$DRY_RUN" == "1" ]]; then
  RELEASE_DRY_RUN=1 release/scripts/bump-version.sh
else
  RELEASE_DRY_RUN=0 release/scripts/bump-version.sh
fi

validate_version_file
if [[ -z "$RELEASE_TAG" ]]; then
  RELEASE_TAG="v$VERSION"
fi
resolve_release_notes_file
determine_release_notes_range

load_release_target
check_release_not_exists

echo "Release run configuration:"
echo "  dry_run=$DRY_RUN"
echo "  run_build=$RUN_BUILD"
echo "  run_notes=$RUN_NOTES"
echo "  run_website=$RUN_WEBSITE"
echo "  run_publish=$RUN_PUBLISH"
echo "  allow_dirty=$ALLOW_DIRTY"
echo "  allow_existing_artifacts=$ALLOW_EXISTING_ARTIFACTS"
echo "  stable_product=$RELEASE_STABLE_PRODUCT"
echo "  stable_version=$RELEASE_STABLE_VERSION"
echo "  eap_product=$RELEASE_EAP_PRODUCT"
echo "  eap_version=$RELEASE_EAP_VERSION"
echo "  release_tag=$RELEASE_TAG"
echo "  release_target=$RELEASE_TARGET"
echo "  release_notes_file=$RELEASE_NOTES_FILE"
echo "  release_notes_previous_tag=${RELEASE_NOTES_PREVIOUS_TAG:-none}"
echo "  release_notes_commit_range=$RELEASE_NOTES_COMMIT_RANGE"
echo "  release_zip_file=$RELEASE_ZIP_FILE"
echo "  release_devrig_zip_file=${RELEASE_DEVRIG_ZIP_FILE:-<resolved at publish time>}"

if [[ "$RUN_NOTES" == "1" ]]; then
  run_release_notes_agents
  commit_release_notes_if_needed
fi

if [[ "$RUN_BUILD" == "1" ]]; then
  RELEASE_STABLE_PRODUCT="$RELEASE_STABLE_PRODUCT" \
    RELEASE_STABLE_VERSION="$RELEASE_STABLE_VERSION" \
    RELEASE_EAP_PRODUCT="$RELEASE_EAP_PRODUCT" \
    RELEASE_EAP_VERSION="$RELEASE_EAP_VERSION" \
    RELEASE_NOTES_VERSION="$VERSION" \
    release/scripts/run-builder.sh bash release/scripts/run-release-build-matrix.sh
fi

if [[ "$RUN_PUBLISH" == "1" ]]; then
  validate_publish_inputs
  # Resolve publish target from the website (public) repo
  PUBLISH_TARGET="$(git -C "$ROOT_DIR/website" rev-parse HEAD)"

  # gh uses the source filename as the asset name — EULA is uploaded as "EULA",
  # the plugin zip as mcp-steroid-<version>-<hash>.zip, and the devrig CLI zip as
  # devrig-<version>-<hash>.zip.
  # Normalize again right before publish so a hand-edited or pre-existing
  # wrapped notes file (e.g. under --skip-notes) still publishes without
  # newline-><br> mid-sentence breaks in the GitHub release body.
  PUBLISH_NOTES_FILE="$(mktemp -t "release-notes-publish.${VERSION}.XXXXXX")"
  unwrap_release_notes "$RELEASE_NOTES_FILE" "$PUBLISH_NOTES_FILE"

  gh release create "$RELEASE_TAG" "$RELEASE_ZIP_FILE" "$RELEASE_DEVRIG_ZIP_FILE" "$ROOT_DIR/EULA" \
    --repo jonnyzzz/mcp-steroid \
    --target "$PUBLISH_TARGET" \
    --notes-file "$PUBLISH_NOTES_FILE"

  # Tag both repos
  git tag -a "$RELEASE_TAG" -m "release: $VERSION" HEAD 2>/dev/null || true
  git push origin "$RELEASE_TAG" 2>/dev/null || true
  git -C "$ROOT_DIR/website" tag -a "$RELEASE_TAG" -m "release: $VERSION" HEAD 2>/dev/null || true
  git -C "$ROOT_DIR/website" push origin "$RELEASE_TAG" 2>/dev/null || true

  # Upload to JetBrains Marketplace
  release/scripts/publish-marketplace.sh "$RELEASE_ZIP_FILE"

  echo "Publish stage completed."
else
  echo "Publish stage skipped."
fi

if [[ "$RUN_WEBSITE" == "1" ]]; then
  "$RUN_AGENT_SCRIPT" codex \
    /Users/jonnyzzz/Work/mcp-steroid \
    /Users/jonnyzzz/Work/mcp-steroid/release/prompts/website-release-page.md
fi
