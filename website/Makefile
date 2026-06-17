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

.PHONY: dev build clean setup update-config ensure-public-repo copy-root-files

VERSION := $(shell cat ../VERSION)

# Look up the .zip download URL for the published release matching VERSION on the public
# jonnyzzz/mcp-steroid repository. Uses the GitHub REST API directly via curl + jq so the
# lookup runs without any authentication token (public repos allow anonymous reads at
# 60 req/hour per IP, which is far more than this Makefile ever spends). Falls back from
# the `vX.Y.Z` tag form to the bare `X.Y.Z` form, mirroring the prior `gh` invocation.
RELEASE_ZIP_URL := $(shell \
    curl -fsSL "https://api.github.com/repos/jonnyzzz/mcp-steroid/releases/tags/v$(VERSION)" 2>/dev/null \
    | jq -r 'first(.assets[]? | select((.name | startswith("mcp-steroid")) and (.name | endswith(".zip")))) | .browser_download_url // empty' \
    || curl -fsSL "https://api.github.com/repos/jonnyzzz/mcp-steroid/releases/tags/$(VERSION)" 2>/dev/null \
    | jq -r 'first(.assets[]? | select((.name | startswith("mcp-steroid")) and (.name | endswith(".zip")))) | .browser_download_url // empty' \
)

# First time setup - clone the public repository
setup:
	git clone git@github.com:jonnyzzz/mcp-steroid.git mcp-steroid-public

# Ensure the output directory exists (auto-setup)
ensure-public-repo:
	@mkdir -p mcp-steroid-public

# Copy EULA and LICENSE from repository root into static/ so the website serves both
copy-root-files:
	cp ../EULA static/EULA
	cp ../LICENSE static/LICENSE

# Update version in hugo.toml, generate version.json + updatePlugins.xml.
update-config: ensure-public-repo copy-root-files
	@echo "Updating version to $(VERSION)"
	@sed -i.bak 's/version = ".*"/version = "$(VERSION)"/' hugo.toml && rm -f hugo.toml.bak
	@echo "Generating version.json"
	@echo '{"version-base": "$(VERSION)"}' > static/version.json
	@if [ -z "$(RELEASE_ZIP_URL)" ]; then echo "ERROR: Could not find release ZIP for version $(VERSION). Is the release published on jonnyzzz/mcp-steroid?" >&2; exit 1; fi
	@echo "Generating updatePlugins.xml (url: $(RELEASE_ZIP_URL))"
	@./scripts/generate-update-plugins-xml.sh "$(VERSION)" "$(RELEASE_ZIP_URL)" > static/updatePlugins.xml
	@xmllint --noout static/updatePlugins.xml 2>/dev/null && echo "updatePlugins.xml is valid XML" || echo "WARNING: xmllint not available, skipping XML validation"
	@mkdir -p mcp-steroid-public/mcp-steroid-plugin

# Start development server with live reload
dev: update-config
	docker compose up dev

# Build the site to mcp-steroid-public/mcp-steroid-plugin.
#
# `--user` keeps the produced files owned by the invoking host user instead of root,
# so subsequent runs (and TC's "git clean -fdx" between builds) can remove them
# without privilege issues.
#
# Emits a TeamCity service message at the end so CI picks the rendered site up as a
# regular build artifact. Harmless no-op for local runs and GitHub Actions — both
# just print the line to stdout. See:
#   https://www.jetbrains.com/help/teamcity/service-messages.html#Publishing+Artifacts+while+the+Build+is+Still+in+Progress
build: update-config
	@# Remove symlinks in output dir — they escape Docker mount boundary and break Hugo stat
	@find mcp-steroid-public/mcp-steroid-plugin -type l -delete 2>/dev/null || true
	docker compose run --rm --user "$$(id -u):$$(id -g)" build
	@# `=> website.zip` (with the .zip extension) tells TeamCity to bundle the matched
	@# files into a single archive artifact instead of publishing the directory tree
	@# verbatim. See https://www.jetbrains.com/help/teamcity/configuring-general-settings.html#Artifact+Paths
	@echo "##teamcity[publishArtifacts '$(CURDIR)/mcp-steroid-public/mcp-steroid-plugin/** => website.zip']"

# Clean generated files (recreated by build)
clean:
	find mcp-steroid-public/mcp-steroid-plugin -mindepth 1 -delete 2>/dev/null || true
