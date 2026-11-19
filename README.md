# Burrow

Lightweight, vanilla-style friends list and instant singleplayer world hosting for Minecraft (Fabric) — no port forwarding, no extra accounts, and no bloat.

## Dependencies

- **Minecraft** (Fabric, client-side only)
- **Fabric Loader** (`>= 0.20.1`)
- **Fabric API**

*(Both the host and joining players need Burrow installed.)*

## Configuration

Burrow generates `.minecraft/config/burrow.json` automatically on first launch:

```json
{
  "serverUrl": "wss://burrow.meeyah.dev",
  "connectAutomatically": true,
  "incognito": false,
  "discordRichPresence": true
}
```

- **`serverUrl`** — Signaling/relay server to connect to. Uses `wss://burrow.meeyah.dev` by default; change this to point at your own self-hosted [burrow-server](https://github.com/itsameeyah/burrow-server).
- **`connectAutomatically`** — Connect to Burrow on game launch.
- **`incognito`** — Hide your world/server details from friends and pause Discord Rich Presence.
- **`discordRichPresence`** — Enable or disable local Discord Rich Presence.

## Building

```bash
./gradlew build
```

Output jar is written to `build/libs/`.

## License

MIT
