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
# Post-deploy LIVE canonicalization checks (issue #332, parent #319).
#
# Curls the live site and verifies the redirect + SEO contract:
#   - https://devrig.dev/                 -> 200 over HTTPS
#   - http://devrig.dev/                  -> redirect to https://devrig.dev/
#   - https://www.devrig.dev/             -> redirect to the apex https://devrig.dev/
#   - https://mcp-steroid.jonnyzzz.com/<p> -> redirect to https://devrig.dev/<p> (path preserved)
#   - homepage carries <link rel=canonical ...devrig.dev>
#   - /robots.txt carries the "Sitemap: https://devrig.dev/sitemap.xml" line
#   - /sitemap.xml <loc> entries are on https://devrig.dev
#
# Redirect codes: any of 301/302/307/308 is accepted (Jonny intentionally keeps
# a 307 on the legacy-host rule), so permanence is NOT asserted. What IS asserted
# is the redirect TARGET (scheme + host + preserved path).
#
# Run manually:  bash website/scripts/check-live.sh
# Prints PASS/FAIL per check and exits non-zero if any check fails.

set -u

APEX="https://devrig.dev"
CURL="curl -sS --max-time 20"

fail=0
ok()  { printf 'PASS: %s\n' "$1"; }
err() { printf 'FAIL: %s\n' "$1"; fail=1; }

is_redirect() { # $1 = http code
  case "$1" in 301|302|307|308) return 0 ;; *) return 1 ;; esac
}

# http_code + first-hop Location (redirect_url), space-separated, no following.
code_and_location() {
  $CURL -o /dev/null -w '%{http_code} %{redirect_url}' "$1" 2>/dev/null
}

echo "Live canonicalization checks against $APEX"
echo

# --- 1. apex over HTTPS -> 200 ---------------------------------------------
code=$($CURL -o /dev/null -w '%{http_code}' "$APEX/" 2>/dev/null || echo "000")
if [ "$code" = "200" ]; then
  ok "$APEX/ -> 200"
else
  err "$APEX/ -> $code (expected 200)"
fi

# --- 2. http apex -> redirect to https --------------------------------------
set -- $(code_and_location "http://devrig.dev/")
code="${1:-000}"; loc="${2:-}"
if is_redirect "$code" && printf '%s' "$loc" | grep -q "^https://devrig\.dev/"; then
  ok "http://devrig.dev/ -> $code $loc"
else
  err "http://devrig.dev/ -> $code $loc (expected 3xx to https://devrig.dev/)"
fi

# --- 3. www -> redirect to apex ---------------------------------------------
set -- $(code_and_location "https://www.devrig.dev/")
code="${1:-000}"; loc="${2:-}"
if is_redirect "$code" && printf '%s' "$loc" | grep -q "^https://devrig\.dev/"; then
  ok "https://www.devrig.dev/ -> $code $loc"
else
  err "https://www.devrig.dev/ -> $code $loc (expected 3xx to apex https://devrig.dev/)"
fi

# --- 4. legacy host -> redirect to apex, path preserved ---------------------
LEGACY_PATH="/docs/"
set -- $(code_and_location "https://mcp-steroid.jonnyzzz.com$LEGACY_PATH")
code="${1:-000}"; loc="${2:-}"
if is_redirect "$code" && [ "$loc" = "$APEX$LEGACY_PATH" ]; then
  ok "legacy https://mcp-steroid.jonnyzzz.com$LEGACY_PATH -> $code $loc (path preserved)"
else
  err "legacy https://mcp-steroid.jonnyzzz.com$LEGACY_PATH -> $code $loc (expected 3xx to $APEX$LEGACY_PATH)"
fi

# --- 5. homepage canonical on devrig.dev ------------------------------------
home=$($CURL "$APEX/" 2>/dev/null || true)
canon=$(printf '%s' "$home" | grep -oE 'rel="?canonical"? href="?[^" >]+' \
  | grep -oE 'https?://[^" >]+' | head -1)
if printf '%s' "$canon" | grep -q "^https://devrig\.dev/"; then
  ok "homepage canonical -> $canon"
else
  err "homepage canonical -> '$canon' (expected https://devrig.dev/...)"
fi

# --- 6. robots.txt Sitemap line ---------------------------------------------
robots=$($CURL "$APEX/robots.txt" 2>/dev/null || true)
if printf '%s\n' "$robots" | grep -Eq "^[[:space:]]*Sitemap:[[:space:]]*$APEX/sitemap\.xml[[:space:]]*$"; then
  ok "/robots.txt has 'Sitemap: $APEX/sitemap.xml'"
else
  err "/robots.txt missing 'Sitemap: $APEX/sitemap.xml' line"
fi

# --- 7. sitemap.xml <loc> on devrig.dev -------------------------------------
sitemap=$($CURL "$APEX/sitemap.xml" 2>/dev/null || true)
locs=$(printf '%s' "$sitemap" | grep -oE '<loc>[^<]+</loc>' | sed -e 's|<loc>||' -e 's|</loc>||')
if [ -z "$locs" ]; then
  err "/sitemap.xml has no <loc> entries (fetch failed or empty)"
else
  bad=$(printf '%s\n' "$locs" | grep -vE "^$APEX(/|$)" || true)
  if [ -n "$bad" ]; then
    err "/sitemap.xml <loc> NOT on $APEX:
$bad"
  else
    n=$(printf '%s\n' "$locs" | wc -l | tr -d ' ')
    ok "/sitemap.xml present, all $n <loc> on $APEX"
  fi
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "live checks: FAILED"
  exit 1
fi
echo "live checks: PASSED"
