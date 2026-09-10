package dev.deathnote.realistic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Extends corrupt block erasure to the player's effective render distance.
 *
 * The old DeathNoteService still performs its close-range pass and owns the
 * legacy state. This engine progressively sweeps loaded chunks throughout the
 * visible area, so large render distances do not cause a single giant scan.
 */
public final class RenderDistanceBlockSweeper {
    private static final Pattern VALID_REGISTRY_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int CHUNKS_PER_PLAYER_PER_SCAN = 8;
    private static final int VERTICAL_SCAN_RADIUS = 64;
    private static final int MAX_BLOCKS_ERASED_PER_SCAN = 8192;
    private static final int MAX_HISTORY_PER_BLOCK = 100000;
    private static final int SAVE_INTERVAL_TICKS = 100;

    private static final String DIRECTORY = "deathnote_realistic";
    private static final String FILE_NAME = "corrupt_blocks_render.json";

    private static final Set<String> CONDEMNED_BLOCK_IDS = new HashSet<>();
    private static final Map<String, LinkedHashMap<ErasedBlockKey, BlockState>> HISTORY = new HashMap<>();
    private static final Map<UUID, Integer> PLAYER_SCAN_CURSOR = new HashMap<>();

    private static long ticks;
    private static boolean dirty;

    private RenderDistanceBlockSweeper() {}

    public static void condemn(String rawId) {
        String id = normalizeRegistryId(rawId);
        if (id == null) return;
        if (CONDEMNED_BLOCK_IDS.add(id)) dirty = true;
        HISTORY.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
    }

    public static void restore(ServerPlayer writer, String rawId) {
        String id = normalizeRegistryId(rawId);
        if (id == null) return;

        if (CONDEMNED_BLOCK_IDS.remove(id)) dirty = true;
        LinkedHashMap<ErasedBlockKey, BlockState> history = HISTORY.get(id);
        if (history == null || history.isEmpty()) return;

        MinecraftServer server = ((ServerLevel) writer.level()).getServer();
        Iterator<Map.Entry<ErasedBlockKey, BlockState>> iterator = history.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ErasedBlockKey, BlockState> entry = iterator.next();
            ErasedBlockKey key = entry.getKey();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || !level.isLoaded(key.pos())) continue;
            if (!level.getBlockState(key.pos()).isAir()) {
                iterator.remove();
                dirty = true;
                continue;
            }

            level.setBlockAndUpdate(key.pos(), entry.getValue());
            iterator.remove();
            dirty = true;
        }

        if (history.isEmpty()) HISTORY.remove(id);
    }

    public static int effectiveRenderChunks(ServerPlayer player) {
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        int requested = Math.max(2, player.requestedViewDistance());
        int serverLimit = Math.max(2, server.getPlayerList().getViewDistance());
        return Math.min(requested, serverLimit);
    }

    public static int effectiveRenderRadiusBlocks(ServerPlayer player) {
        return effectiveRenderChunks(player) * 16;
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (!CONDEMNED_BLOCK_IDS.isEmpty() && ticks % SCAN_INTERVAL_TICKS == 0) {
            sweep(server);
        }
        if (dirty && ticks % SAVE_INTERVAL_TICKS == 0) save(server);
    }

    private static void sweep(MinecraftServer server) {
        int erased = 0;

        playerLoop:
        for (ServerPlayer anchor : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) anchor.level();
            int renderChunks = effectiveRenderChunks(anchor);
            int diameter = renderChunks * 2 + 1;
            int totalSlots = diameter * diameter;
            int cursor = Math.floorMod(PLAYER_SCAN_CURSOR.getOrDefault(anchor.getUUID(), 0), totalSlots);
            int attempted = 0;
            int processedChunks = 0;
            int centerChunkX = anchor.blockPosition().getX() >> 4;
            int centerChunkZ = anchor.blockPosition().getZ() >> 4;

            while (attempted < totalSlots && processedChunks < CHUNKS_PER_PLAYER_PER_SCAN) {
                int slot = (cursor + attempted) % totalSlots;
                attempted++;

                int dx = slot % diameter - renderChunks;
                int dz = slot / diameter - renderChunks;
                if (dx * dx + dz * dz > renderChunks * renderChunks) continue;

                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) continue;

                processedChunks++;
                erased += sweepLoadedChunk(level, anchor, chunkX, chunkZ, MAX_BLOCKS_ERASED_PER_SCAN - erased);
                if (erased >= MAX_BLOCKS_ERASED_PER_SCAN) {
                    PLAYER_SCAN_CURSOR.put(anchor.getUUID(), (cursor + attempted) % totalSlots);
                    break playerLoop;
                }
            }

            PLAYER_SCAN_CURSOR.put(anchor.getUUID(), (cursor + Math.max(1, attempted)) % totalSlots);
        }

        if (erased > 0) dirty = true;
    }

    private static int sweepLoadedChunk(ServerLevel level, ServerPlayer anchor, int chunkX, int chunkZ, int limit) {
        if (limit <= 0) return 0;

        int erased = 0;
        int minY = Math.max(level.getMinY(), anchor.blockPosition().getY() - VERTICAL_SCAN_RADIUS);
        int maxY = Math.min(level.getMaxY() - 1, anchor.blockPosition().getY() + VERTICAL_SCAN_RADIUS);
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = minX + localX;
                int z = minZ + localZ;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!CONDEMNED_BLOCK_IDS.contains(blockId)) continue;

                    remember(blockId, level.dimension(), pos, state);
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    erased++;
                    if (erased >= limit) return erased;
                }
            }
        }
        return erased;
    }

    private static void remember(String blockId, ResourceKey<Level> dimension, BlockPos pos, BlockState state) {
        LinkedHashMap<ErasedBlockKey, BlockState> history = HISTORY.computeIfAbsent(blockId, ignored -> new LinkedHashMap<>());
        history.putIfAbsent(new ErasedBlockKey(dimension, pos.immutable()), state);

        while (history.size() > MAX_HISTORY_PER_BLOCK) {
            Iterator<ErasedBlockKey> iterator = history.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    public static void load(MinecraftServer server) {
        CONDEMNED_BLOCK_IDS.clear();
        HISTORY.clear();
        PLAYER_SCAN_CURSOR.clear();

        // Import condemned block types from the older state file as migration.
        Path legacy = server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).resolve("state.json");
        if (Files.exists(legacy)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(legacy, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("condemnedBlocks")) {
                    for (JsonElement element : root.getAsJsonArray("condemnedBlocks")) {
                        String id = normalizeRegistryId(element.getAsString());
                        if (id != null) CONDEMNED_BLOCK_IDS.add(id);
                    }
                }
            } catch (Exception exception) {
                DeathNoteMod.LOGGER.warn("Could not import legacy condemned blocks", exception);
            }
        }

        Path path = statePath(server);
        if (!Files.exists(path)) {
            dirty = !CONDEMNED_BLOCK_IDS.isEmpty();
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("condemnedBlocks")) {
                for (JsonElement element : root.getAsJsonArray("condemnedBlocks")) {
                    String id = normalizeRegistryId(element.getAsString());
                    if (id != null) CONDEMNED_BLOCK_IDS.add(id);
                }
            }

            if (root.has("history")) {
                JsonObject histories = root.getAsJsonObject("history");
                for (Map.Entry<String, JsonElement> group : histories.entrySet()) {
                    String id = normalizeRegistryId(group.getKey());
                    if (id == null || !group.getValue().isJsonArray()) continue;

                    LinkedHashMap<ErasedBlockKey, BlockState> history = new LinkedHashMap<>();
                    for (JsonElement element : group.getValue().getAsJsonArray()) {
                        if (!element.isJsonObject()) continue;
                        JsonObject saved = element.getAsJsonObject();
                        try {
                            Identifier dimensionId = parseIdentifier(saved.get("dimension").getAsString());
                            Identifier blockId = parseIdentifier(saved.get("block").getAsString());
                            if (dimensionId == null || blockId == null) continue;

                            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
                            var block = BuiltInRegistries.BLOCK.getValue(blockId);
                            if (block == null) continue;
                            BlockPos pos = new BlockPos(saved.get("x").getAsInt(), saved.get("y").getAsInt(), saved.get("z").getAsInt());
                            history.put(new ErasedBlockKey(dimension, pos), block.defaultBlockState());
                        } catch (RuntimeException ignored) {
                            DeathNoteMod.LOGGER.warn("Ignoring invalid render-distance block history entry for {}", id);
                        }
                    }
                    if (!history.isEmpty()) HISTORY.put(id, history);
                }
            }
            dirty = false;
        } catch (Exception exception) {
            DeathNoteMod.LOGGER.error("Could not load render-distance corrupt block state", exception);
        }
    }

    public static void save(MinecraftServer server) {
        if (!dirty) return;

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray condemned = new JsonArray();
        CONDEMNED_BLOCK_IDS.stream().sorted().forEach(condemned::add);
        root.add("condemnedBlocks", condemned);

        JsonObject histories = new JsonObject();
        for (Map.Entry<String, LinkedHashMap<ErasedBlockKey, BlockState>> group : HISTORY.entrySet()) {
            JsonArray entries = new JsonArray();
            for (Map.Entry<ErasedBlockKey, BlockState> entry : group.getValue().entrySet()) {
                JsonObject saved = new JsonObject();
                saved.addProperty("dimension", entry.getKey().dimension().identifier().toString());
                saved.addProperty("x", entry.getKey().pos().getX());
                saved.addProperty("y", entry.getKey().pos().getY());
                saved.addProperty("z", entry.getKey().pos().getZ());
                saved.addProperty("block", BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()).toString());
                entries.add(saved);
            }
            histories.add(group.getKey(), entries);
        }
        root.add("history", histories);

        Path path = statePath(server);
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException exception) {
            DeathNoteMod.LOGGER.error("Could not save render-distance corrupt block state", exception);
        }
    }

    private static Path statePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).resolve(FILE_NAME);
    }

    private static String normalizeRegistryId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase();
        if (id.isEmpty()) return null;
        if (!id.contains(":")) id = "minecraft:" + id;
        return VALID_REGISTRY_ID.matcher(id).matches() ? id : null;
    }

    private static Identifier parseIdentifier(String raw) {
        if (raw == null) return null;
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator >= raw.length() - 1) return null;
        return Identifier.fromNamespaceAndPath(raw.substring(0, separator), raw.substring(separator + 1));
    }

    private record ErasedBlockKey(ResourceKey<Level> dimension, BlockPos pos) {}
}
