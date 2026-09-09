package dev.deathnote.realistic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.deathnote.realistic.network.DeathNoteWritePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DeathNoteService {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern VALID_REGISTRY_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final int ENTITY_RADIUS = 64;

    private static final int GLOBAL_BLOCK_SCAN_RADIUS = 24;
    private static final int BLOCK_SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_BLOCKS_ERASED_PER_SCAN = 2048;
    private static final int MAX_RESTORE_HISTORY_PER_BLOCK = 50000;
    private static final int SAVE_INTERVAL_TICKS = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STATE_DIRECTORY = "deathnote_realistic";
    private static final String STATE_FILE = "state.json";

    private static final Map<UUID, PendingDeath> PENDING = new HashMap<>();
    private static final Map<UUID, Long> WRITER_COOLDOWN_UNTIL = new HashMap<>();
    private static final Set<String> CONDEMNED_BLOCK_IDS = new HashSet<>();
    private static final Map<String, LinkedHashMap<ErasedBlockKey, BlockState>> ERASED_BLOCK_HISTORY = new HashMap<>();
    private static final Map<String, UUID> CONDEMNED_PLAYERS = new HashMap<>();
    private static long ticks;
    private static boolean stateDirty;

    private DeathNoteService() {}

    public static void submit(ServerPlayer writer, DeathNoteWritePayload payload) {
        if (!isHoldingDeathNote(writer)) {
            message(writer, "message.deathnote_realistic.must_hold", ChatFormatting.RED);
            return;
        }

        String kind = payload.targetKind() == null ? "player" : payload.targetKind().trim().toLowerCase();
        switch (kind) {
            case "entity" -> eraseNearestEntity(writer, payload.targetName());
            case "block" -> condemnBlockGlobally(writer, payload.targetName());
            case "restore_block" -> restoreBlockGlobally(writer, payload.targetName());
            case "restore_player" -> restorePlayer(writer, payload.targetName());
            default -> submitPlayer(writer, payload);
        }
    }

    private static void submitPlayer(ServerPlayer writer, DeathNoteWritePayload payload) {
        String name = payload.targetName() == null ? "" : payload.targetName().trim();
        if (name.length() > DeathNoteRules.MAX_PLAYER_NAME_LENGTH || !VALID_NAME.matcher(name).matches()) {
            message(writer, "message.deathnote_realistic.invalid_name", ChatFormatting.RED);
            return;
        }

        long cooldownUntil = WRITER_COOLDOWN_UNTIL.getOrDefault(writer.getUUID(), 0L);
        if (ticks < cooldownUntil) {
            long seconds = Math.max(1L, (cooldownUntil - ticks + 19L) / 20L);
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.cooldown", seconds).withStyle(ChatFormatting.RED));
            return;
        }

        MinecraftServer server = ((ServerLevel) writer.level()).getServer();
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) {
            message(writer, "message.deathnote_realistic.not_online", ChatFormatting.RED);
            return;
        }

        if (!DeathNoteRules.ALLOW_SELF_TARGET && target.getUUID().equals(writer.getUUID())) {
            message(writer, "message.deathnote_realistic.no_self", ChatFormatting.RED);
            return;
        }

        if (PENDING.containsKey(target.getUUID())) {
            message(writer, "message.deathnote_realistic.already_pending", ChatFormatting.RED);
            return;
        }

        DeathCause cause = DeathCause.fromId(payload.causeId());
        long dueTick = ticks + DeathNoteRules.DEATH_DELAY_SECONDS * 20L;
        PENDING.put(target.getUUID(), new PendingDeath(target.getUUID(), writer.getUUID(), target.getName().getString(), cause, dueTick));
        WRITER_COOLDOWN_UNTIL.put(writer.getUUID(), ticks + DeathNoteRules.WRITER_COOLDOWN_SECONDS * 20L);

        writer.sendSystemMessage(
            Component.translatable("message.deathnote_realistic.accepted", target.getName().getString(), DeathNoteRules.DEATH_DELAY_SECONDS)
                .withStyle(ChatFormatting.DARK_RED)
        );
    }

    private static void restorePlayer(ServerPlayer writer, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() > DeathNoteRules.MAX_PLAYER_NAME_LENGTH || !VALID_NAME.matcher(name).matches()) {
            message(writer, "message.deathnote_realistic.invalid_name", ChatFormatting.RED);
            return;
        }

        UUID removed = CONDEMNED_PLAYERS.remove(name.toLowerCase());
        if (removed == null) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.player_restore_none", name).withStyle(ChatFormatting.YELLOW));
            return;
        }

        markDirty();
        writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.player_restored", name).withStyle(ChatFormatting.GREEN));
        ServerPlayer target = ((ServerLevel) writer.level()).getServer().getPlayerList().getPlayer(removed);
        if (target != null) {
            target.sendSystemMessage(Component.translatable("message.deathnote_realistic.player_restored_target").withStyle(ChatFormatting.GREEN));
        }
    }

    public static void enforceCondemnedPlayer(ServerPlayer player) {
        if (!CONDEMNED_PLAYERS.containsValue(player.getUUID())) return;
        player.sendSystemMessage(Component.translatable("message.deathnote_realistic.respawn_blocked").withStyle(ChatFormatting.DARK_RED));
        if (player.isAlive()) {
            player.kill((ServerLevel) player.level());
        }
    }

    private static void eraseNearestEntity(ServerPlayer writer, String rawId) {
        String id = normalizeRegistryId(rawId);
        if (id == null) {
            message(writer, "message.deathnote_realistic.invalid_registry_id", ChatFormatting.RED);
            return;
        }

        ServerLevel level = (ServerLevel) writer.level();
        List<Entity> matches = level.getEntities(
            writer,
            writer.getBoundingBox().inflate(ENTITY_RADIUS),
            entity -> !entity.isRemoved() && id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
        );

        Entity target = matches.stream()
            .min(Comparator.comparingDouble(writer::distanceToSqr))
            .orElse(null);

        if (target == null) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.entity_not_found", id, ENTITY_RADIUS).withStyle(ChatFormatting.RED));
            return;
        }

        String displayName = target.getName().getString();
        target.discard();
        writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.entity_erased", displayName, id).withStyle(ChatFormatting.DARK_RED));
    }

    private static void condemnBlockGlobally(ServerPlayer writer, String rawId) {
        String id = normalizeRegistryId(rawId);
        if (id == null) {
            message(writer, "message.deathnote_realistic.invalid_registry_id", ChatFormatting.RED);
            return;
        }

        CONDEMNED_BLOCK_IDS.add(id);
        ERASED_BLOCK_HISTORY.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
        markDirty();

        int erasedNow = eraseCondemnedBlocks(((ServerLevel) writer.level()).getServer(), MAX_BLOCKS_ERASED_PER_SCAN);
        writer.sendSystemMessage(Component.translatable(
            "message.deathnote_realistic.block_erasure_activated",
            id,
            erasedNow,
            GLOBAL_BLOCK_SCAN_RADIUS
        ).withStyle(ChatFormatting.DARK_RED));
    }

    private static void restoreBlockGlobally(ServerPlayer writer, String rawId) {
        String id = normalizeRegistryId(rawId);
        if (id == null) {
            message(writer, "message.deathnote_realistic.invalid_registry_id", ChatFormatting.RED);
            return;
        }

        CONDEMNED_BLOCK_IDS.remove(id);
        LinkedHashMap<ErasedBlockKey, BlockState> history = ERASED_BLOCK_HISTORY.remove(id);
        markDirty();
        if (history == null || history.isEmpty()) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.block_restore_none", id).withStyle(ChatFormatting.YELLOW));
            return;
        }

        MinecraftServer server = ((ServerLevel) writer.level()).getServer();
        int restored = 0;
        for (Map.Entry<ErasedBlockKey, BlockState> entry : history.entrySet()) {
            ErasedBlockKey key = entry.getKey();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) continue;

            BlockPos pos = key.pos();
            if (!level.isLoaded(pos)) continue;
            if (!level.getBlockState(pos).isAir()) continue;

            level.setBlockAndUpdate(pos, entry.getValue());
            restored++;
        }

        writer.sendSystemMessage(Component.translatable(
            "message.deathnote_realistic.block_restore_done",
            id,
            restored,
            history.size()
        ).withStyle(ChatFormatting.GREEN));
    }

    private static int eraseCondemnedBlocks(MinecraftServer server, int limit) {
        if (CONDEMNED_BLOCK_IDS.isEmpty() || server.getPlayerList().getPlayers().isEmpty()) return 0;

        Set<Long> visited = new HashSet<>();
        int erased = 0;

        outer:
        for (ServerPlayer anchor : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) anchor.level();
            BlockPos center = anchor.blockPosition();
            int minY = Math.max(level.getMinY(), center.getY() - GLOBAL_BLOCK_SCAN_RADIUS);
            int maxY = Math.min(level.getMaxY() - 1, center.getY() + GLOBAL_BLOCK_SCAN_RADIUS);

            for (int x = center.getX() - GLOBAL_BLOCK_SCAN_RADIUS; x <= center.getX() + GLOBAL_BLOCK_SCAN_RADIUS; x++) {
                for (int z = center.getZ() - GLOBAL_BLOCK_SCAN_RADIUS; z <= center.getZ() + GLOBAL_BLOCK_SCAN_RADIUS; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        long key = pos.asLong() ^ ((long) level.dimension().identifier().hashCode() << 32);
                        if (!visited.add(key)) continue;

                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) continue;

                        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (!CONDEMNED_BLOCK_IDS.contains(blockId)) continue;

                        rememberErasedBlock(blockId, level.dimension(), pos, state);
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        erased++;
                        if (erased >= limit) break outer;
                    }
                }
            }
        }

        if (erased > 0) markDirty();
        return erased;
    }

    private static void rememberErasedBlock(String blockId, ResourceKey<Level> dimension, BlockPos pos, BlockState state) {
        LinkedHashMap<ErasedBlockKey, BlockState> history = ERASED_BLOCK_HISTORY.computeIfAbsent(blockId, ignored -> new LinkedHashMap<>());
        ErasedBlockKey key = new ErasedBlockKey(dimension, pos.immutable());
        history.putIfAbsent(key, state);

        while (history.size() > MAX_RESTORE_HISTORY_PER_BLOCK) {
            Iterator<ErasedBlockKey> iterator = history.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private static String normalizeRegistryId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase();
        if (id.isEmpty()) return null;
        if (!id.contains(":")) id = "minecraft:" + id;
        return VALID_REGISTRY_ID.matcher(id).matches() ? id : null;
    }

    public static void tick(MinecraftServer server) {
        ticks++;

        if (!CONDEMNED_BLOCK_IDS.isEmpty() && ticks % BLOCK_SCAN_INTERVAL_TICKS == 0) {
            eraseCondemnedBlocks(server, MAX_BLOCKS_ERASED_PER_SCAN);
        }

        if (stateDirty && ticks % SAVE_INTERVAL_TICKS == 0) {
            saveState(server);
        }

        if (PENDING.isEmpty()) return;

        Iterator<Map.Entry<UUID, PendingDeath>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingDeath pending = iterator.next().getValue();
            if (pending.dueTick() > ticks) continue;

            ServerPlayer target = server.getPlayerList().getPlayer(pending.targetId());
            if (target != null && target.isAlive()) {
                CONDEMNED_PLAYERS.put(pending.targetName().toLowerCase(), target.getUUID());
                markDirty();
                target.sendSystemMessage(causeMessage(pending.cause()));
                target.kill((ServerLevel) target.level());
            }
            iterator.remove();
        }
    }

    public static void loadState(MinecraftServer server) {
        CONDEMNED_PLAYERS.clear();
        CONDEMNED_BLOCK_IDS.clear();
        ERASED_BLOCK_HISTORY.clear();

        Path path = statePath(server);
        if (!Files.exists(path)) {
            stateDirty = false;
            DeathNoteMod.LOGGER.info("No persistent Death Note state found yet.");
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();

            JsonObject players = root.has("condemnedPlayers") ? root.getAsJsonObject("condemnedPlayers") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
                try {
                    CONDEMNED_PLAYERS.put(entry.getKey().toLowerCase(), UUID.fromString(entry.getValue().getAsString()));
                } catch (IllegalArgumentException ignored) {
                    DeathNoteMod.LOGGER.warn("Ignoring invalid condemned player UUID for {}", entry.getKey());
                }
            }

            if (root.has("condemnedBlocks")) {
                for (JsonElement element : root.getAsJsonArray("condemnedBlocks")) {
                    String id = normalizeRegistryId(element.getAsString());
                    if (id != null) CONDEMNED_BLOCK_IDS.add(id);
                }
            }

            JsonObject histories = root.has("erasedBlockHistory") ? root.getAsJsonObject("erasedBlockHistory") : new JsonObject();
            for (Map.Entry<String, JsonElement> historyEntry : histories.entrySet()) {
                String blockId = normalizeRegistryId(historyEntry.getKey());
                if (blockId == null || !historyEntry.getValue().isJsonArray()) continue;

                LinkedHashMap<ErasedBlockKey, BlockState> history = new LinkedHashMap<>();
                for (JsonElement element : historyEntry.getValue().getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    JsonObject saved = element.getAsJsonObject();
                    try {
                        Identifier dimensionId = parseIdentifier(saved.get("dimension").getAsString());
                        Identifier savedBlockId = parseIdentifier(saved.get("block").getAsString());
                        if (dimensionId == null || savedBlockId == null) continue;

                        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
                        var block = BuiltInRegistries.BLOCK.getValue(savedBlockId);
                        if (block == null) continue;

                        BlockPos pos = new BlockPos(saved.get("x").getAsInt(), saved.get("y").getAsInt(), saved.get("z").getAsInt());
                        history.put(new ErasedBlockKey(dimension, pos), block.defaultBlockState());
                    } catch (RuntimeException ignored) {
                        DeathNoteMod.LOGGER.warn("Ignoring invalid restored block entry for {}", blockId);
                    }
                }
                if (!history.isEmpty()) ERASED_BLOCK_HISTORY.put(blockId, history);
            }

            stateDirty = false;
            DeathNoteMod.LOGGER.info(
                "Loaded persistent Death Note state: {} condemned player(s), {} condemned block type(s), {} restore history group(s).",
                CONDEMNED_PLAYERS.size(), CONDEMNED_BLOCK_IDS.size(), ERASED_BLOCK_HISTORY.size()
            );
        } catch (Exception exception) {
            DeathNoteMod.LOGGER.error("Failed to load persistent Death Note state from {}", path, exception);
        }
    }

    public static void saveState(MinecraftServer server) {
        Path path = statePath(server);
        try {
            Files.createDirectories(path.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("version", 1);

            JsonObject players = new JsonObject();
            CONDEMNED_PLAYERS.forEach((name, uuid) -> players.addProperty(name, uuid.toString()));
            root.add("condemnedPlayers", players);

            JsonArray blocks = new JsonArray();
            CONDEMNED_BLOCK_IDS.stream().sorted().forEach(blocks::add);
            root.add("condemnedBlocks", blocks);

            JsonObject histories = new JsonObject();
            for (Map.Entry<String, LinkedHashMap<ErasedBlockKey, BlockState>> historyEntry : ERASED_BLOCK_HISTORY.entrySet()) {
                JsonArray entries = new JsonArray();
                for (Map.Entry<ErasedBlockKey, BlockState> entry : historyEntry.getValue().entrySet()) {
                    ErasedBlockKey key = entry.getKey();
                    JsonObject saved = new JsonObject();
                    saved.addProperty("dimension", key.dimension().identifier().toString());
                    saved.addProperty("x", key.pos().getX());
                    saved.addProperty("y", key.pos().getY());
                    saved.addProperty("z", key.pos().getZ());
                    saved.addProperty("block", BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()).toString());
                    entries.add(saved);
                }
                histories.add(historyEntry.getKey(), entries);
            }
            root.add("erasedBlockHistory", histories);

            Path temporary = path.resolveSibling(STATE_FILE + ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            stateDirty = false;
        } catch (IOException exception) {
            DeathNoteMod.LOGGER.error("Failed to save persistent Death Note state to {}", path, exception);
        }
    }

    public static void shutdown(MinecraftServer server) {
        if (stateDirty) saveState(server);
    }

    private static Path statePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(STATE_DIRECTORY).resolve(STATE_FILE);
    }

    private static Identifier parseIdentifier(String raw) {
        if (raw == null) return null;
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator >= raw.length() - 1) return null;
        return Identifier.fromNamespaceAndPath(raw.substring(0, separator), raw.substring(separator + 1));
    }

    private static void markDirty() {
        stateDirty = true;
    }

    private static Component causeMessage(DeathCause cause) {
        return Component.translatable("message.deathnote_realistic.cause." + cause.id()).withStyle(ChatFormatting.DARK_RED);
    }

    private static boolean isHoldingDeathNote(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.DEATH_NOTE) || player.getOffhandItem().is(ModItems.DEATH_NOTE);
    }

    private static void message(ServerPlayer player, String key, ChatFormatting color) {
        player.sendSystemMessage(Component.translatable(key).withStyle(color));
    }

    private record ErasedBlockKey(ResourceKey<Level> dimension, BlockPos pos) {}
    private record PendingDeath(UUID targetId, UUID writerId, String targetName, DeathCause cause, long dueTick) {}
}
