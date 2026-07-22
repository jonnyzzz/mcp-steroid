#!/bin/sh
# Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Build-time canonicalization assertions (issue #332, parent #319).
#
# Runs against the Hugo-generated output dir (public/) AFTER the build and exits
# non-zero if any canonicalization signal is wrong. Wired into `make build`.
#
# What it checks:
#   1. every rendered .html page carries a <link rel="canonical"> tag;
#   2. the homepage canonical is exactly https://devrig.dev/;
#   3. the canonicalization signals (canonical href, og:url, og:image,
#      twitter:image) all point at https://devrig.dev;
#   4. sitemap.xml exists and every <loc> is on https://devrig.dev;
#   5. robots.txt exists and carries "Sitemap: https://devrig.dev/sitemap.xml";
#   6. the OLD host mcp-steroid.jonnyzzz.com appears in NONE of the
#      canonicalization signals above.
#
# NOTE on the old-host check (6): it is scoped to the canonicalization SIGNALS,
# not to a blanket scan of the whole output. Archived release notes and the
# "Website launched at mcp-steroid.jonnyzzz.com" changelog line legitimately
# mention the old host as historical prose; those are content, not
# canonicalization bugs, and must not fail the build. Only the SEO signals that
# #319 governs (canonical/og/twitter/sitemap/robots) are asserted host-clean.
#
# POSIX sh, no heavy deps: grep + find only. Handles both minified (unquoted
# attributes) and non-minified (quoted) HTML, since alias/redirect stubs are not
# minified.

set -eu

NEW_BASE="https://devrig.dev"
OLD_HOST="mcp-steroid.jonnyzzz.com"

# Output dir: first arg, else the Makefile's default output path relative to website/.
DIR="${1:-mcp-steroid-public/mcp-steroid-plugin}"

fail=0
err() { printf 'FAIL: %s\n' "$1" >&2; fail=1; }
ok()  { printf 'PASS: %s\n' "$1"; }

if [ ! -d "$DIR" ]; then
  printf 'FAIL: output dir not found: %s\n' "$DIR" >&2
  exit 1
fi

printf 'Asserting canonicalization signals in: %s\n' "$DIR"

# --- 1. every HTML page has a canonical link -------------------------------
missing=""
for f in $(find "$DIR" -type f -name '*.html'); do
  if ! grep -Eq 'rel="?canonical"?' "$f"; then
    missing="$missing $f"
  fi
done
if [ -n "$missing" ]; then
  err "HTML pages without <link rel=canonical>:$missing"
else
  ok "every HTML page has <link rel=canonical>"
fi

# --- 2. homepage canonical == https://devrig.dev/ --------------------------
HOME_HTML="$DIR/index.html"
if [ ! -f "$HOME_HTML" ]; then
  err "homepage index.html not found"
else
  home_canon=$(grep -oE 'rel="?canonical"? href="?[^" >]+' "$HOME_HTML" \
    | grep -oE 'https?://[^" >]+' | head -1)
  if [ "$home_canon" = "$NEW_BASE/" ]; then
    ok "homepage canonical == $NEW_BASE/"
  else
    err "homepage canonical is '$home_canon' (expected $NEW_BASE/)"
  fi
fi

# --- 3. canonical/og/twitter signal URLs all on devrig.dev -----------------
# Extract each signal's URL, tolerating quoted and unquoted attributes.
extract() { # $1 = ERE matching "<attr-name> <url-attr>=<url>"
  grep -rhoE "$1" --include='*.html' "$DIR" 2>/dev/null | grep -oE 'https?://[^" >]+' || true
}
signal_urls=$(
  {
    extract 'rel="?canonical"? href="?[^" >]+'
    extract 'property="?og:url"? content="?[^" >]+'
    extract 'property="?og:image"? content="?[^" >]+'
    extract 'name="?twitter:image"? content="?[^" >]+'
    extract 'name="?twitter:url"? content="?[^" >]+'
  } | sort -u
)
offhost=$(printf '%s\n' "$signal_urls" | grep -v '^$' | grep -vE "^$NEW_BASE(/|$)" || true)
if [ -n "$offhost" ]; then
  err "canonicalization signal URLs NOT on $NEW_BASE:
$offhost"
else
  ok "all canonical/og/twitter signal URLs are on $NEW_BASE"
fi

# --- 4. sitemap.xml exists and all <loc> on devrig.dev ---------------------
SITEMAP="$DIR/sitemap.xml"
if [ ! -f "$SITEMAP" ]; then
  err "sitemap.xml not found"
else
  bad_loc=$(grep -oE '<loc>[^<]+</loc>' "$SITEMAP" \
    | sed -e 's|<loc>||' -e 's|</loc>||' \
    | grep -vE "^$NEW_BASE(/|$)" || true)
  if [ -n "$bad_loc" ]; then
    err "sitemap <loc> entries NOT on $NEW_BASE:
$bad_loc"
  else
    n=$(grep -oE '<loc>[^<]+</loc>' "$SITEMAP" | wc -l | tr -d ' ')
    ok "sitemap.xml present, all $n <loc> on $NEW_BASE"
  fi
fi

# --- 5. robots.txt exists and carries the Sitemap line ---------------------
ROBOTS="$DIR/robots.txt"
if [ ! -f "$ROBOTS" ]; then
  err "robots.txt not found"
elif grep -Eq "^[[:space:]]*Sitemap:[[:space:]]*$NEW_BASE/sitemap\.xml[[:space:]]*$" "$ROBOTS"; then
  ok "robots.txt has 'Sitemap: $NEW_BASE/sitemap.xml'"
else
  err "robots.txt missing 'Sitemap: $NEW_BASE/sitemap.xml' line"
fi

# --- 6. old host absent from the canonicalization signals ------------------
old_in_signals=$(printf '%s\n' "$signal_urls" | grep -F "$OLD_HOST" || true)
old_in_sitemap=""
[ -f "$SITEMAP" ] && old_in_sitemap=$(grep -F "$OLD_HOST" "$SITEMAP" || true)
old_in_robots=""
[ -f "$ROBOTS" ] && old_in_robots=$(grep -F "$OLD_HOST" "$ROBOTS" || true)
if [ -n "$old_in_signals$old_in_sitemap$old_in_robots" ]; then
  err "old host $OLD_HOST found in canonicalization signals:
$old_in_signals$old_in_sitemap$old_in_robots"
else
  ok "old host $OLD_HOST absent from all canonicalization signals"
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "canonicalization assertions: FAILED"
  exit 1
fi
echo "canonicalization assertions: PASSED"
