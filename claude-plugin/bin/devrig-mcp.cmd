#!/bin/sh
:; # ===== POSIX sh (Windows cmd skips every :; line as a label) =====
:; DEVRIG="$HOME/.mcp-steroid/bin/devrig"
:; if [ -x "$DEVRIG" ]; then exec "$DEVRIG" mcp; fi
:; ROOT="${CLAUDE_PLUGIN_ROOT:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)}"
:; os=$(uname -s | tr '[:upper:]' '[:lower:]'); case "$os" in darwin) os=darwin;; linux) os=linux;; esac
:; arch=$(uname -m); case "$arch" in x86_64|amd64) arch=amd64;; arm64|aarch64) arch=arm64;; esac
:; BOOT="$ROOT/bin/bootstrap-$os-$arch"
:; if [ ! -x "$BOOT" ]; then echo "devrig: no bootstrap for $os-$arch at $BOOT" >&2; exit 1; fi
:; exec "$BOOT"
@echo off
rem ===== Windows cmd =====
set "DEVRIG=%USERPROFILE%\.mcp-steroid\bin\devrig.cmd"
if not exist "%DEVRIG%" goto :devrig_bootstrap
"%DEVRIG%" mcp
exit /b %ERRORLEVEL%
:devrig_bootstrap
set "ROOT=%CLAUDE_PLUGIN_ROOT%"
if "%ROOT%"=="" set "ROOT=%~dp0.."
set "ARCH=amd64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=arm64"
set "BOOT=%ROOT%\bin\bootstrap-windows-%ARCH%.exe"
if not exist "%BOOT%" goto :devrig_nobootstrap
"%BOOT%"
exit /b %ERRORLEVEL%
:devrig_nobootstrap
1>&2 echo devrig: no bootstrap at %BOOT%
exit /b 1
