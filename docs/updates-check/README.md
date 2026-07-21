# Update Checker

The MCP Steroid plugin includes an automatic update checker that notifies users when a new version is available.

## Overview

The update checker:
- Fetches version information from `https://devrig.dev/version.json`
- Compares the remote version with the currently installed plugin version
- Shows a balloon notification **once per IDE session** when a newer version is detected
- Continues checking periodically even after detecting an update (so the check doesn't stop)

## Version Endpoint

The plugin checks the following endpoint:

```
GET https://devrig.dev/version.json?intellij-version=<IJ-BUILD>
```

### Query Parameters

| Parameter | Description | Example |
|-----------|-------------|---------|
| `intellij-version` | Full IntelliJ build number | `IJ-253.12345` |

### Response Format

```json
{
  "version-base": "0.86.0"
}
```

### User-Agent

The request includes a custom User-Agent:
```
MCP-Steroid/<plugin-version> (IntelliJ/<ij-build>)
```

Example:
```
MCP-Steroid/0.86.0-SNAPSHOT-2026-01-30-12-34 (IntelliJ/IU-253.12345)
```

## Version Comparison

Version comparison uses IntelliJ's `StringUtil.compareVersionNumbers()` which handles:
- Standard semantic versions: `1.2.3`
- Versions with pre-release suffixes: `1.2.3-SNAPSHOT`
- Build metadata: `1.2.3-SNAPSHOT-2026-01-30-12-34`

The plugin extracts the base version (before `-SNAPSHOT` or first `-`) for comparison.

## Configuration

The update checker can be configured via IntelliJ Registry keys:

| Registry Key | Default | Description |
|--------------|---------|-------------|
| `mcp.steroid.updates.enabled` | `true` | Enable/disable automatic update checks |
| `mcp.steroid.updates.checkIntervalHours` | `1` | Hours between update checks |

To access Registry settings:
1. Open `Help > Find Action` (Cmd+Shift+A / Ctrl+Shift+A)
2. Type "Registry" and select it
3. Search for `mcp.steroid.updates`

## Implementation Details

### Service Lifecycle

- `UpdateChecker` is an application-level service (`@Service(Service.Level.APP)`)
- Initialized via `SteroidsMcpServerStartupActivity` when any project opens
- Uses coroutine scope injected by the platform for lifecycle management
- Periodic checks run in `Dispatchers.IO`

### Timing

1. **Initial check**: 30 seconds after IDE start (allows IDE to fully initialize)
2. **Subsequent checks**: Every N hours (configurable, default 1 hour)
3. **Notification**: Shown only once per IDE session

### Notification

Uses IntelliJ's notification system with:
- Group ID: `jonnyzzz.mcp.steroid.updates`
- Type: `BALLOON` (non-sticky balloon notification)
- Severity: `INFORMATION`

## Testing

The `UpdateChecker` exposes properties for testing:

```kotlin
val checker = UpdateChecker.getInstance()
checker.checkForUpdates() // Trigger manual check
checker.lastFetchedVersion // Last version from remote
checker.updateDetected // Whether an update was found
```

## Architecture

```
┌─────────────────────────────────────┐
│   SteroidsMcpServerStartupActivity  │
│   (triggers on project open)        │
└─────────────────┬───────────────────┘
                  │ getInstance()
                  ▼
┌─────────────────────────────────────┐
│         UpdateChecker               │
│   (application-level service)       │
│                                     │
│  ┌───────────────────────────────┐  │
│  │  Coroutine Scope (IO)         │  │
│  │  - Initial delay: 30s         │  │
│  │  - Check interval: 1h         │  │
│  │  - Loop: check → wait → check │  │
│  └───────────────────────────────┘  │
│                                     │
│  - HttpRequests for fetch          │
│  - StringUtil for comparison       │
│  - NotificationGroupManager        │
└─────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│  https://devrig.dev  │
│         /version.json               │
└─────────────────────────────────────┘
```

## Files

- `UpdateChecker.kt` - Main service implementation
- `plugin.xml` - Notification group and registry key registration
- `docs/updates-check/README.md` - This documentation
