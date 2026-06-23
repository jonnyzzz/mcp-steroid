@echo off
:: MCP Steroid — stdio MCP bridge launcher for Windows.
:: stdout is the MCP JSON-RPC channel: nothing must be written to it before exec.
:: All diagnostic output goes to stderr.

set DEVRIG=%USERPROFILE%\.mcp-steroid\bin\devrig.cmd

if not exist "%DEVRIG%" (
  echo mcp-steroid: devrig not found at %DEVRIG% 1>&2
  echo mcp-steroid: run /mcp-steroid:setup in Claude to install it 1>&2
  exit /b 1
)

call "%DEVRIG%" mcp
exit /b %ERRORLEVEL%
