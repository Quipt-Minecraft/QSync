# QSync Non-Synced Server Configuration

## Overview

QSync now supports marking servers as "synced" or "non-synced". When a player moves between servers:
- **Between two synced servers**: Player inventory, health, exp, etc. are synchronized
- **To/From a non-synced server**: Data is NOT synchronized; player keeps whatever they have

## How It Works

### Problem It Solves
Previously, if a player would:
1. Go to a synced server (e.g., "lobby")
2. Add an item to their inventory
3. Go to a non-synced server (e.g., "minigame")
4. Return to a synced server

The new item would **disappear** because the old cached data would be applied, overwriting the new changes.

### Solution
The new `ServerConfig` class ensures:
- Data is only captured when leaving a synced server
- Data is only applied when entering a synced server
- Cache is invalidated when entering a non-synced server, preventing stale data from being applied later

## Configuration

In `velocity/src/main/java/live/qsmc/qsync/QSync.java`, the default synced servers are initialized:

```java
serverConfig.initializeDefaults("lobby", "bac");
```

### Customizing Synced Servers

You can modify this line to include your actual synced server names:

```java
serverConfig.initializeDefaults("lobby", "survival", "creative");
```

Or add/remove servers programmatically:

```java
ServerConfig config = QSync.instance().getServerConfig();
config.markSynced("myserver");  // Add a synced server
config.isSynced("myserver");    // Check if synced
```

## Future Enhancements

To make this configurable via a YAML/TOML file:
1. Create a `qsync-config.yml` file
2. List synced servers in configuration
3. Load configuration at plugin startup
4. Example structure:

```yaml
qsync:
  synced-servers:
    - lobby
    - survival
    - creative
  # Non-synced servers (implicit; any server not listed)
  # - minigame
  # - parkour
```

## Logging

When data sync is skipped, you'll see logs like:
```
[QSync-Sync] Skipping SYNC_REQUEST for Player — target server minigame is not synced
[QSync-Sync] Skipping SYNC_APPLY for Player — target server minigame is not synced (invalidating cache)
```

This helps you verify that your non-synced servers are configured correctly.

