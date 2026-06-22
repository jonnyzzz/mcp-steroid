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

.PHONY: dev build clean setup update-config ensure-public-repo

VERSION := $(shell cat ../VERSION)

# First time setup - clone the public repository
setup:
	git clone git@github.com:jonnyzzz/mcp-steroid.git mcp-steroid-public

# Ensure the output directory exists (auto-setup)
ensure-public-repo:
	@mkdir -p mcp-steroid-public

# Update version in hugo.toml, then generate every static file via the single :website-gen task.
#
# :website-gen:generateWebsite produces ALL generated static files into website/build/generated-static
# (a gitignored build/ dir Hugo merges via its `staticDir` list): version.json + updatePlugins.xml
# (resolving the published GitHub release itself — downloads the plugin ZIP, reads the real version +
# since-build from META-INF/plugin.xml), install.sh + install.ps1 (via :installer-gen:generateInstaller),
# and EULA + LICENSE (copied from the repo root). One task, one output dir — no out-of-band file copying.
update-config: ensure-public-repo
	@echo "Updating version to $(VERSION)"
	@sed -i.bak 's/version = ".*"/version = "$(VERSION)"/' hugo.toml && rm -f hugo.toml.bak
	@echo "Generating website static files (version.json + updatePlugins.xml + install.sh + install.ps1) via :website-gen (Kotlin)"
	@cd .. && ./gradlew --console=plain --info --stacktrace :website-gen:generateWebsite
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
