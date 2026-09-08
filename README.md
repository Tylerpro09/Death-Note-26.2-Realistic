# Death Note 26.2 Realistic

Modernized Fabric port of **Death-Note-1.21.X** for **Minecraft Java 26.2**.

> Fictional gameplay mechanic only. The "Death Note" affects Minecraft entities/players inside the game and has no real-world functionality.

## Status

- Target: Minecraft Java 26.2
- Mod loader: Fabric
- Java: 25
- Fabric Loader: 0.19.5
- Fabric API: 0.159.0+26.2
- Version: 2.0.0-alpha.1

## What changed from the 1.21 mod

- Migrated from Yarn-era 1.21 APIs to Mojang/unobfuscated 26.2 names.
- Replaced reflection into `BookEditScreen` with a dedicated `DeathNoteScreen`.
- Replaced command-string execution (`kill`, `title`, selectors, etc.) with typed server-side Java logic.
- Replaced `new Thread()` / `Thread.sleep()` with a server-tick scheduler.
- Server validates every request: held item, target format, online target, self-target setting, cooldown and duplicate pending entries.
- Username input is capped at Minecraft's 16-character player-name limit.
- Default delay is 40 seconds, inspired by the fictional rules; it is centralized in `DeathNoteRules`.
- Added three fictional in-game causes: heart attack, accident and mysterious death.
- Spanish and English localization.
- Dedicated-server-safe client/common split.

## Gameplay

1. Obtain a Death Note with `/give @s deathnote_realistic:death_note`.
2. Hold it in either hand and right-click.
3. Enter the exact name of an **online player**.
4. Pick a cause.
5. Press **Write name**.
6. The server validates the request and schedules the fictional effect.

By default:
- Delay: 40 seconds.
- Writer cooldown: 10 seconds.
- Self targeting: disabled.
- Only online players can be targeted.
- A target can have only one pending entry.

These defaults are in `src/main/java/dev/deathnote/realistic/DeathNoteRules.java`.

## Build

Install **JDK 25** and **Gradle 9.x**, then run:

```bash
gradle build
```

The JAR will be generated under `build/libs/`.

To add the standard Gradle wrapper locally:

```bash
gradle wrapper --gradle-version 9.5.0
./gradlew build
```

On Windows after generating the wrapper:

```powershell
.\gradlew.bat build
```

## GitHub Actions

The included workflow builds with Java 25 and Gradle 9.5.0 on every push/PR and uploads the built JAR as an artifact.

## Attribution

Based on the MIT-licensed project:
https://github.com/ericafk0001/Death-Note-1.21.X

Original license copyright: **Copyright (c) 2025 Eric Lin**. The original notice is preserved in `LICENSE` as required by MIT.

## Notes

This alpha intentionally targets players only. The old fallback that interpreted arbitrary text as an entity type was removed because it made validation weaker and behavior ambiguous. Mob targeting can be reintroduced later with a proper entity selector UI and server-side allowlist.
