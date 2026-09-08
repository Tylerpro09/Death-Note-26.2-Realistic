# Migration notes: 1.21 -> 26.2

## Toolchain

| Original | Port |
|---|---|
| Minecraft 1.21 | Minecraft 26.2 |
| Java 21 | Java 25 |
| Yarn mappings | Mojang/unobfuscated names |
| Fabric Loader 0.16.10 | 0.19.5 |
| Fabric API 0.102.0+1.21 | 0.159.0+26.2 |
| Loom 1.9 snapshot | Loom 1.17 snapshot |

## Architecture changes

### Removed reflection
The original inspected `BookEditScreen` fields via reflection and assumed the first `List` field contained page text. That is brittle across mappings and game updates. The port uses a normal custom `Screen` with an `EditBox`.

### Removed command injection surface
The original constructed server commands from client-controlled strings. The port never concatenates player input into a command. It resolves an exact online player by name and calls game APIs directly.

### Removed sleeping thread
The original spawned a thread and slept for five seconds before executing server actions. The port stores pending fictional deaths and advances them on `ServerTickEvents.END_SERVER_TICK`.

### Networking
The port uses 26.2 `CustomPacketPayload`, `StreamCodec`, `PayloadTypeRegistry.serverboundPlay()` and `ClientPlayNetworking.send()`.

### Sided code
Client GUI code lives in `src/client/java`; server/common code lives in `src/main/java`, matching the modern Fabric template split-source-set layout.
