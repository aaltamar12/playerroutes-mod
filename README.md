# PlayerRoutes Mod

Server-side NeoForge mod that tracks player movements and renders a 2D map of your Minecraft world. Works with the [PlayerRoutes Web](../playerroutes-web) companion app for real-time visualization.

![NeoForge](https://img.shields.io/badge/NeoForge-1.21.x-orange) ![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x-green) ![Side](https://img.shields.io/badge/Side-Server-blue)

## Features

### Player Tracking
- **Automatic Position Recording**: Tracks all players on the server
- **Smart Sampling**: Records positions based on movement, not time
- **Multi-dimension Support**: Tracks Overworld, Nether, and End
- **Session Management**: Groups player activity into sessions (login to logout)
- **Statistics**: Distance traveled, time played, points recorded

### Map Rendering
- **Chunk-based Tiles**: Renders 512x512 pixel tiles (32x32 chunks)
- **Automatic Updates**: Re-renders tiles when players explore new areas
- **Block Colors**: Accurate color mapping for all Minecraft blocks
- **Height Shading**: Terrain elevation visualization
- **Water Depth**: Shows water depth with transparency

### WebSocket Server
- **Real-time Updates**: Streams player positions to connected clients
- **Token Authentication**: Secure access with configurable token
- **Command Execution**: Execute teleport commands from the web UI
- **Batched Updates**: Efficient data transmission

### Data Storage
- **JSON Storage**: Human-readable session files
- **Organized Structure**: Sessions grouped by player UUID
- **Tile Cache**: PNG images stored for fast serving

## Installation

1. Install [NeoForge](https://neoforged.net/) for Minecraft 1.21.x
2. Download the latest `playerroutes-x.x.x.jar` from [Releases](../../releases)
3. Place the JAR in your server's `mods/` folder
4. Start the server to generate the config file
5. Configure `playerroutes-server.toml` (see Configuration below)
6. Restart the server

## Configuration

The config file is located at `config/playerroutes-server.toml`:

```toml
[tracking]
# Interval between position samples (ms)
sampleIntervalMs = 2000

# Minimum blocks moved to record a new point
minMoveBlocks = 2

# Maximum idle time before forcing a point record (ms)
maxIdleIntervalMs = 10000

# Maximum points stored per active session
maxPointsPerSession = 5000

[storage]
# Storage provider: "json" (MongoDB support planned)
provider = "json"

# Directory for JSON storage (relative to server root)
jsonDir = "playerroutes-data"

[websocket]
# WebSocket server port
port = 8765

# Authentication token - CHANGE THIS!
token = "change-me-in-production"

# Batch interval for WebSocket updates (ms)
batchIntervalMs = 500
```

### Important Configuration

**Always change the default token!** Generate a secure token:

```bash
# Linux/Mac
openssl rand -hex 32

# Or use any password generator
```

## Data Directory Structure

```
playerroutes-data/
├── sessions/
│   └── {player-uuid}/
│       └── {session-id}.json     # Session data files
├── tiles/
│   ├── minecraft__overworld/
│   │   └── {x}_{z}.png           # Overworld tiles
│   ├── minecraft__the_nether/
│   │   └── {x}_{z}.png           # Nether tiles
│   └── minecraft__the_end/
│       └── {x}_{z}.png           # End tiles
└── players.json                   # Player UUID to name mapping
```

## Session Data Format

Each session is stored as a JSON file:

```json
{
  "_id": "uuid-session-id",
  "playerUuid": "player-minecraft-uuid",
  "playerName": "PlayerName",
  "startedAt": 1703700000000,
  "endedAt": 1703703600000,
  "active": false,
  "stats": {
    "samples": 1500,
    "distanceXZ": 12345.67,
    "distanceXYZ": 13456.78
  },
  "path": [
    {
      "x": 100,
      "y": 64,
      "z": 200,
      "dim": "minecraft:overworld",
      "t": 1703700000000
    }
  ]
}
```

## WebSocket Protocol

### Connection

Connect to `ws://server:8765?token=your-token`

### Messages from Server

**Init** (sent on connection):
```json
{
  "type": "init",
  "activeSessions": [...],
  "worldTime": 6000
}
```

**Player Update** (batched):
```json
{
  "type": "player_update",
  "sessionId": "...",
  "playerName": "...",
  "point": { "x": 100, "y": 64, "z": 200, "dim": "minecraft:overworld", "t": 1703700000000 }
}
```

**Session Start**:
```json
{
  "type": "session_start",
  "session": { ... }
}
```

**Session End**:
```json
{
  "type": "session_end",
  "sessionId": "...",
  "stats": { ... }
}
```

**World Time** (every 5 seconds):
```json
{
  "type": "world_time",
  "time": 6000
}
```

**Command Response**:
```json
{
  "type": "command_response",
  "success": true,
  "message": "Teleported PlayerA to PlayerB"
}
```

### Messages to Server

**Teleport Command**:
```json
{
  "type": "teleport",
  "player": "PlayerName",
  "targetPlayer": "TargetPlayer"
}
```

or with coordinates:
```json
{
  "type": "teleport",
  "player": "PlayerName",
  "x": 100,
  "y": 64,
  "z": 200
}
```

## Building from Source

### Requirements
- Java 21+
- Gradle 8.x

### Build

```bash
./gradlew build
```

The JAR will be in `build/libs/`.

### Development

```bash
# Run client
./gradlew runClient

# Run server
./gradlew runServer
```

## Compatibility

- **Minecraft**: 1.21.x
- **NeoForge**: 21.x.x
- **Side**: Server-only (no client installation needed)
- **Multiplayer**: Fully supported

## Performance

The mod is designed to be lightweight:

- **Sampling**: Only records when players actually move
- **Batching**: WebSocket updates are batched to reduce network traffic
- **Async Rendering**: Tile rendering happens in a background thread
- **Memory Limits**: Configurable max points per session

## Troubleshooting

### WebSocket connection refused
- Check that port 8765 is open in your firewall
- Verify the token matches your web app configuration

### Tiles not rendering
- Ensure the server has write permissions to `playerroutes-data/`
- Check server logs for rendering errors

### High memory usage
- Reduce `maxPointsPerSession` in config
- Increase `minMoveBlocks` to record fewer points

## Support the Project

If you find PlayerRoutes useful, consider supporting its development:

[![PayPal](https://img.shields.io/badge/PayPal-Donate-blue?logo=paypal)](https://paypal.me/alfonsovlog)

Your support helps maintain and improve the mod!

## License

MIT

## See Also

- [PlayerRoutes Web](../playerroutes-web) - Web UI companion app
