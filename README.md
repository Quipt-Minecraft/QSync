# QSync

Fabric-only player sync for Velocity networks.

## What changed

This project now targets:
- `velocity/` — the proxy plugin
- `fabric/` — the backend mod

Paper support has been removed from the build.

## Features

- Full backend handoff flow through Velocity
- Player data sync using the saved player NBT payload
  - inventory
  - ender chest
  - health / hunger / experience
  - potion effects
  - attributes and other persisted data through Minecraft's native player-data loader when available
- Cross-server chat relay through the proxy
- Join/leave announcements only from the proxy
- Suppression of backend `joined the game` / `left the game` messages

## Architecture

### Velocity
- Requests player data from the old Fabric backend before a server switch
- Caches the serialized payload briefly while the player is in transit
- Applies the cached payload on the destination backend after connect
- Relays chat messages to every other backend server
- Announces network join/leave events once at the proxy layer

### Fabric
- Captures player data from the live player into compressed vanilla-style NBT
- Applies player data using Minecraft's own NBT loading methods via reflection when possible
- Falls back to manual restoration of critical gameplay state if mappings change
- Receives proxy-relayed chat and broadcasts it locally on that backend
- Suppresses backend join/leave system messages

## Build

```powershell
Set-Location "C:\Users\Quick\IdeaProjects\qsync-velocity"
.\gradlew.bat build --no-daemon
```

## Output artifacts

- `velocity/build/libs/qsync-velocity-*.jar`
- `fabric/build/libs/qsync-fabric-*.jar`

## Deployment

1. Put the Velocity jar on the proxy.
2. Put the Fabric jar on every backend Fabric server behind that proxy.
3. Ensure the backend servers are reached through Velocity and not directly by players.
4. Keep the plugin messaging channel `qsync:data` available between proxy and backends.

## Notes

- The sync apply path preserves the destination server's current position/rotation so server transfers do not unexpectedly teleport players.
- The full-restore path is intentionally reflective to stay resilient across mapping/signature changes in newer Minecraft versions.

