# RuneLite Dev MCP

A read-only [Model Context Protocol](https://modelcontextprotocol.io) server for RuneLite. Exposes the live game state to AI assistants (Claude, etc.) over JSON-RPC + REST on `localhost:3000`.

Read-only by design — only game-state observation is exposed. No clicking, no input, no automation.

## Demo

Once connected, you can hold a normal conversation with Claude about what's happening in your client:

> **You:** What's the strongest amulet in my bank?
>
> *Claude calls `bank` with `n=amulet`, reads the matching items + stats:*
>
> **Claude:** You have an Amulet of fury (+10 strength, +15 attack bonuses) and an Amulet of glory (+8 strength). The fury is the strongest.

> **You:** What NPCs are around me right now?
>
> *Claude calls `npc` with default radius:*
>
> **Claude:** Five within ~10 tiles — two Bankers, a Banker tutor, a Doomsayer, and a Captain Lawgof.

> **You:** How did I move in the last 20 ticks?
>
> *Claude calls `buffer` with `t=-20`, reads sparse per-tick deltas:*
>
> **Claude:** You stood still for the first 8 ticks, then walked 6 tiles north over the next 12 ticks. Twelve of the ticks had no other changes and were omitted.

The same data is also available as raw JSON over `POST /mcp` for non-Claude clients.

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
| `state` | `inc` (CSV: player, resources, inventory, equipment, npcs, skills) | Player + world snapshot |
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

All responses include `_meta.gameTick` (OSRS runs at 600ms/tick).

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
