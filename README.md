# RuneLite Dev MCP

A read-only [Model Context Protocol](https://modelcontextprotocol.io) server for RuneLite. Exposes the live game state to AI assistants (Claude, etc.) over JSON-RPC + REST on `localhost:3000`.

Read-only by design — only game-state observation is exposed. No clicking, no input, no automation.

## Demo

Tools are invoked over JSON-RPC 2.0 at `POST /mcp` using the standard MCP `tools/call` method. Each call returns a result envelope wrapping the tool's JSON output as a `text` content block. Responses below show the tool output (the value of `result.content[0].text`, parsed) — the wrapping envelope is omitted for brevity.

### Filter the bank for amulets

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "bank",
    "arguments": {"n": "amulet"}
  }
}
```

Response (tool output):

```json
{
  "_meta": {"gameTick": 184213},
  "open": true,
  "total": 312,
  "filter": "amulet",
  "matched": 2,
  "items": [
    {"slot": 42, "id": 6585, "name": "Amulet of fury",  "quantity": 1, "actions": ["Withdraw-1", "Withdraw-5", "Withdraw-10"]},
    {"slot": 43, "id": 1712, "name": "Amulet of glory", "quantity": 1, "actions": ["Withdraw-1", "Withdraw-5", "Withdraw-10"]}
  ]
}
```

Note: `bank` returns item identity only (`id`, `name`, `quantity`, `actions`). It does **not** return equipment stats — a client that wants to rank amulets by strength bonus has to map the item IDs against its own data source.

### NPCs around the player

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "npc",
    "arguments": {}
  }
}
```

Response (nearest 15, sorted by Chebyshev/Manhattan distance from the player):

```json
{
  "_meta": {"gameTick": 184215},
  "total": 5,
  "shown": 5,
  "items": [
    {"index": 12, "id": 494,  "name": "Banker",         "pos": [3094, 3492, 0], "hp": [-1, -1], "animation": -1, "actions": ["Bank",    "Examine"], "dist": 2},
    {"index": 18, "id": 495,  "name": "Banker",         "pos": [3093, 3492, 0], "hp": [-1, -1], "animation": -1, "actions": ["Bank",    "Examine"], "dist": 3},
    {"index": 23, "id": 6362, "name": "Banker tutor",   "pos": [3091, 3493, 0], "hp": [-1, -1], "animation": -1, "actions": ["Talk-to", "Examine"], "dist": 4},
    {"index": 31, "id": 3375, "name": "Doomsayer",      "pos": [3088, 3490, 0], "hp": [-1, -1], "animation": -1, "actions": ["Talk-to", "Examine"], "dist": 8},
    {"index": 47, "id": 3105, "name": "Captain Lawgof", "pos": [3084, 3496, 0], "hp": [-1, -1], "animation": -1, "actions": ["Talk-to", "Examine"], "dist": 12}
  ]
}
```

### Player movement over the last 20 ticks (delta buffer)

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "buffer",
    "arguments": {"t": -20, "types": "player"}
  }
}
```

Response (sparse — ticks with no changes are dropped and counted in `ticksOmitted`):

```json
{
  "_meta": {"gameTick": 184230},
  "bufferCapacity": 200,
  "bufferFilled": 184,
  "type": "delta",
  "ticksOmitted": 14,
  "ticks": [
    {"tick": 184219, "timestampMs": 1716579234120, "deltas": {"player": {"pos": [3094, 3493, 0], "runEnergy": 9821}}},
    {"tick": 184221, "timestampMs": 1716579235320, "deltas": {"player": {"pos": [3094, 3494, 0], "runEnergy": 9783}}},
    {"tick": 184223, "timestampMs": 1716579236520, "deltas": {"player": {"pos": [3094, 3495, 0], "runEnergy": 9745}}},
    {"tick": 184225, "timestampMs": 1716579237720, "deltas": {"player": {"pos": [3094, 3496, 0], "runEnergy": 9707}}},
    {"tick": 184227, "timestampMs": 1716579238920, "deltas": {"player": {"pos": [3094, 3497, 0], "runEnergy": 9669}}},
    {"tick": 184229, "timestampMs": 1716579240120, "deltas": {"player": {"pos": [3094, 3498, 0], "runEnergy": 9631}}}
  ]
}
```

The same JSON shapes are returned to any MCP-aware client (Claude, custom scripts, other LLMs) — there is nothing Claude-specific in the protocol.

## Endpoints

- `POST   /mcp` — JSON-RPC 2.0 MCP requests (tool discovery + invocation)
- `GET    /mcp` — server→client SSE stream (streamable HTTP transport, spec `2025-03-26`)
- `DELETE /mcp` — client-initiated session termination
- `GET    /sse`, `GET /mcp/sse` — legacy HTTP+SSE transport (spec `2024-11-05`), kept for backwards compatibility
- `GET    /api/status` — plugin status snapshot
- `GET    /api/telemetry` — diagnostic counters
- `GET    /health`

Session ids are returned as `Mcp-Session-Id` on the response to `initialize`; clients echo it on subsequent requests but the server does not strictly enforce it.

## MCP tools (read-only)

Point-in-time state queries:

| Tool | Args | Purpose |
|------|------|---------|
| `state` | `inc` (CSV: player, resources, inventory, equipment, npcs, players, skills) | Player + world snapshot |
| `npc` | `n` (name), `i` (id CSV), `r` (radius) | NPCs near the player |
| `obj` | `n` (name), `i` (id CSV) | Game objects in the scene |
| `ground` | `n` (name) | Ground items near the player |
| `inv` | `m` (mode: `q` default \| `s`) | Inventory snapshot |
| `equip` | — | Equipped items |
| `bank` | `n` (name substring, case-insensitive) | Bank contents (only when bank is open) |
| `dialog` | — | Current dialogue state |
| `widget` | `m` (`get` \| `pick`), `g` (group), `c` (child) | Widget tree introspection |
| `var` | `m=v`, `varbitId` | Varbit / varplayer values |
| `menu` | — | Right-click menu entries at the cursor |
| `chat` | `lines` (default 10) | Recent chat messages |
| `screenshot` | — | Game viewport PNG. Over MCP `tools/call` it's wrapped as `{"type":"image","data":"<base64>","mimeType":"image/png"}` so MCP-aware clients render it inline. |
| `loginstate` | — | Login state (`LOGGED_IN`, `LOGGING_IN`, etc.) |
| `prayer` | — | Active prayers (list) + prayer point pool (`current` / `max`) |

Historical / event-stream queries (server-side ring buffers, updated every tick):

| Tool | Args | Purpose |
|------|------|---------|
| `buffer` | `t` (default `-5`), `types`, `names`, `ids`, `tile`, `area` | Per-tick state of player / NPCs / objects / ground items / other players / skills / hits. `t > 0` returns a full absolute snapshot at that tick; `t < 0` returns the last `|t|` ticks as sparse deltas with `added` / `removed` / `changed` per entity type. The `skills` type emits a per-skill object with only the changed fields (`gained` XP, `real` level-ups, `boosted` for temporary boosts / damage / regen). The `hits` type emits the list of `HitsplatApplied` events that landed on that tick. Ticks with no matching changes are omitted and counted in `ticksOmitted`. Capacity 200 ticks (~2 min). |
| `actions` | `t` (default 50), `option`, `target`, `opcodes`, `ids`, `since` | Recent `MenuOptionClicked` events: user clicks plus plugin / macro actions invoked through the public menu API (`Client.invokeMenuAction`, `Client.menuAction`). Does **not** catch actions that bypass the menu and send raw packets. Newest-last. Capacity 500 actions. |

All responses include `_meta.gameTick` (OSRS runs at 600ms/tick). Actor responses
include animation, movement, health, interaction target, and recent hitsplat context
where available.

## Download

Pre-built JARs are attached to each [GitHub Release](https://github.com/runbunbun/runelite-dev-mcp/releases/latest) alongside a `.sha256` checksum file. Verify before sideloading:

```sh
shasum -a 256 -c runelite-dev-mcp-*.jar.sha256
```

## Build (from source)

```sh
./gradlew jar
```

This produces `build/libs/runelite-dev-mcp-<version>.jar`.

## Installing the plugin

> **Plugin Hub posture.** This plugin is sideload-only by design. It will not be submitted to the [RuneLite Plugin Hub](https://github.com/runelite/plugin-hub), whose guidelines forbid plugins that expose game state to external automation. The install steps below are the supported path.

> **Security posture.** The server binds to `127.0.0.1` only — it is not reachable from the local network. CORS is restricted to an allow-list (`localhost`, `127.0.0.1`, `vscode-webview://*`); other web origins cannot read responses. There is no authentication on the local socket, so any process running as your user on the same machine can read game state.

Sideloaded plugins are only honored when RuneLite is launched directly via the JVM with `-Drunelite.pluginsdir=...` and `--developer-mode`. **The official `RuneLite.app` launcher does not load sideloaded plugins**, even with `--developer-mode` in its args — that flag is consumed by the launcher, not the client.

### 1. Drop the JAR into the sideload directory

```sh
cp build/libs/runelite-dev-mcp-*.jar ~/.runelite/sideloaded-plugins/
```

### 2. Launch RuneLite via direct JVM invocation

The RuneLite app bundles a JRE and the launcher downloads the client JARs into `~/.runelite/repository2/`. Run the client directly with those JARs on the classpath:

MacOS:

```sh
JAVA="/Applications/RuneLite.app/Contents/Resources/jre/bin/java"
REPO="$HOME/.runelite/repository2"
CP=$(find "$REPO" -name '*.jar' | tr '\n' ':')

"$JAVA" -ea -Xmx768m -Xss2m \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED \
  -Dapple.awt.application.appearance=system \
  -Drunelite.pluginsdir="$HOME/.runelite/sideloaded-plugins" \
  -cp "$CP" \
  net.runelite.client.RuneLite \
  --developer-mode --debug
```

On Linux / Windows, adjust `JAVA` and the `repository2` path accordingly (e.g. `~/.local/share/RuneLite/repository2/` on Linux).

### 3. Enable the plugin

In the RuneLite plugin list, search for **RuneLite Dev MCP** and toggle it on. The server starts on `localhost:3000`.

### Verifying

```sh
curl http://localhost:3000/health         # {"status":"ok"}
curl http://localhost:3000/api/status     # {gameTick, loginState, uptimeMs}
```

## Connecting Claude Code

Once the server is running, register it with Claude Code as a streamable HTTP MCP server:

```sh
claude mcp add --transport http runelite-dev-mcp http://localhost:3000/mcp
```

Or add it manually to `.mcp.json` (project-scoped) or `~/.claude.json` (user-scoped):

```json
{
  "mcpServers": {
    "runelite-dev-mcp": {
      "type": "http",
      "url": "http://localhost:3000/mcp"
    }
  }
}
```

Run `/mcp` in Claude Code to confirm the server connected and the tools loaded. Any other MCP-aware client that speaks streamable HTTP (`2025-03-26`) or the legacy HTTP+SSE transport (`2024-11-05`) should work the same way against the same URL.

## License

MIT License. See [LICENSE](LICENSE).
