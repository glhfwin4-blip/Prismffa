# PvPCore 1.0.0

A starter Paper plugin project for Minecraft 1.21.x. Features:
- Heal to full health and food on player kill
- Combat tag timer, command restrictions, and combat-log punishment
- Configurable kits and cooldowns
- Coins balance, kill rewards, and `/coins`
- `/discord` command
- Minimalistic tab header/footer and sidebar scoreboard
- Optional VPN/proxy checks (disabled by default)

## Build
Requires Java 21 and Maven. From this folder run:

```bash
mvn package
```

The plugin jar will be created in `target/PvPCore-1.0.0.jar`. Put it in the server's `plugins` folder and restart the server.

## Configure
Edit `plugins/PvPCore/config.yml`. Replace `play.example.net` and the Discord invite. Run `/pvpcore reload` after edits.

## Important notes
- GrimAC is a separate anti-cheat plugin. Install GrimAC separately; PvPCore does not replace it.
- `extra-configs/TAB/config.yml` is an optional starting point for the separate TAB plugin. PvPCore already supplies its own header/footer and scoreboard; avoid enabling two scoreboards at once.
- VPN checking is disabled by default. If enabled, it calls ip-api.com's HTTP JSON endpoint on player join and may be affected by rate limits, network access, false positives, and the provider's terms. For production, use a reputable VPN detection provider and a properly configured API key.
- This is source code, not a precompiled jar. Compile it with Maven and test on a staging server before using it publicly.


Blue theme: TAB header and scoreboard use blue accents. The bottom Discord footer has been removed. This ZIP is source code and must be built into a JAR with Maven before installing.
