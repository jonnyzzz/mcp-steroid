#!/bin/sh
:; exec "$HOME/.mcp-steroid/bin/devrig" mcp
@echo off
"%USERPROFILE%\.mcp-steroid\bin\devrig.cmd" mcp
exit /b %ERRORLEVEL%
