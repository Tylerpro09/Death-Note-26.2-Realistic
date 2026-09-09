package dev.deathnote.realistic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent gameplay model for the canon-oriented Death Note rules.
 *
 * This is intentionally Minecraft gameplay logic, not a claim to reproduce
 * every edge case from the source material. It enforces the important rules:
 * ownership, remembered identity, default heart attack, transfer, and the
 * Shinigami-eye bargain.
 */
public final class CanonRulesState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DIRECTORY = "deathnote_realistic";
    private static final String FILE_NAME = "canon_state.json";

    public static final int FACE_MEMORY_SECONDS = 300;
    public static final int FACE_MEMORY_RADIUS = 32;
    private static final long DEFAULT_LIFESPAN_SECONDS = 7L * 24L * 60L * 60L;

    private static UUID ownerId;
    private static String ownerName;
    private static final Map<UUID, Boolean> SHINIGAMI_EYES = new HashMap<>();
    private static final Map<UUID, Long> LIFESPAN_SECONDS = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> LAST_SEEN_TICK = new HashMap<>();
    private static boolean dirty;

    private CanonRulesState() {}

    public static boolean ensureOwner(ServerPlayer player) {
        if (ownerId == null) {
            ownerId = player.getUUID();
            ownerName = player.getName().getString();
            dirty = true;
            player.sendSystemMessage(Component.translatable("message.deathnote_realistic.owner_claimed").withStyle(ChatFormatting.DARK_PURPLE));
            return true;
        }
        if (ownerId.equals(player.getUUID())) return true;
        player.sendSystemMessage(Component.translatable("message.deathnote_realistic.not_owner", ownerName == null ? "?" : ownerName).withStyle(ChatFormatting.RED));
        return false;
    }

    public static boolean hasSeenRecently(ServerPlayer writer, ServerPlayer target, long currentTick) {
        if (writer.getUUID().equals(target.getUUID())) return true;
        Map<UUID, Long> seen = LAST_SEEN_TICK.get(writer.getUUID());
        if (seen == null) return false;
        long last = seen.getOrDefault(target.getUUID(), Long.MIN_VALUE);
        return currentTick - last <= FACE_MEMORY_SECONDS * 20L;
    }

    public static void tick(MinecraftServer server, long currentTick) {
        for (ServerPlayer observer : server.getPlayerList().getPlayers()) {
            for (ServerPlayer target : server.getPlayerList().getPlayers()) {
                if (observer == target) continue;
                if (observer.level() != target.level()) continue;
                if (observer.distanceToSqr(target) > FACE_MEMORY_RADIUS * FACE_MEMORY_RADIUS) continue;
                LAST_SEEN_TICK.computeIfAbsent(observer.getUUID(), ignored -> new HashMap<>())
                    .put(target.getUUID(), currentTick);
            }
        }

        if (currentTick % 100L == 0L) {
            for (ServerPlayer observer : server.getPlayerList().getPlayers()) {
                if (!hasShinigamiEyes(observer)) continue;
                ServerPlayer nearest = null;
                double best = 16.0 * 16.0;
                for (ServerPlayer target : server.getPlayerList().getPlayers()) {
                    if (target == observer || target.level() != observer.level()) continue;
                    double distance = observer.distanceToSqr(target);
                    if (distance < best) {
                        best = distance;
                        nearest = target;
                    }
                }
                if (nearest != null) {
                    long life = getRemainingLifeSeconds(nearest);
                    observer.sendSystemMessage(Component.translatable(
                        "message.deathnote_realistic.eyes_view",
                        nearest.getName().getString(),
                        life
                    ).withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
        }
    }

    public static void transferOwnership(ServerPlayer writer, ServerPlayer target) {
        if (!ensureOwner(writer)) return;
        ownerId = target.getUUID();
        ownerName = target.getName().getString();
        dirty = true;
        writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.transferred", ownerName).withStyle(ChatFormatting.YELLOW));
        target.sendSystemMessage(Component.translatable("message.deathnote_realistic.received_ownership").withStyle(ChatFormatting.DARK_PURPLE));
    }

    public static void acceptShinigamiEyesDeal(ServerPlayer player) {
        if (!ensureOwner(player)) return;
        if (hasShinigamiEyes(player)) {
            player.sendSystemMessage(Component.translatable("message.deathnote_realistic.eyes_already").withStyle(ChatFormatting.YELLOW));
            return;
        }

        long current = getRemainingLifeSeconds(player);
        long reduced = Math.max(1L, current / 2L);
        LIFESPAN_SECONDS.put(player.getUUID(), reduced);
        SHINIGAMI_EYES.put(player.getUUID(), true);
        dirty = true;
        player.sendSystemMessage(Component.translatable("message.deathnote_realistic.eyes_deal_done", reduced).withStyle(ChatFormatting.DARK_PURPLE));
    }

    public static boolean hasShinigamiEyes(ServerPlayer player) {
        return SHINIGAMI_EYES.getOrDefault(player.getUUID(), false);
    }

    public static long getRemainingLifeSeconds(ServerPlayer player) {
        return LIFESPAN_SECONDS.computeIfAbsent(player.getUUID(), ignored -> DEFAULT_LIFESPAN_SECONDS);
    }

    public static void load(MinecraftServer server) {
        ownerId = null;
        ownerName = null;
        SHINIGAMI_EYES.clear();
        LIFESPAN_SECONDS.clear();
        LAST_SEEN_TICK.clear();

        Path path = path(server);
        if (!Files.exists(path)) {
            dirty = false;
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("ownerId") && !root.get("ownerId").isJsonNull()) ownerId = UUID.fromString(root.get("ownerId").getAsString());
            if (root.has("ownerName") && !root.get("ownerName").isJsonNull()) ownerName = root.get("ownerName").getAsString();

            if (root.has("eyes")) {
                for (var entry : root.getAsJsonObject("eyes").entrySet()) {
                    SHINIGAMI_EYES.put(UUID.fromString(entry.getKey()), entry.getValue().getAsBoolean());
                }
            }
            if (root.has("lifespans")) {
                for (var entry : root.getAsJsonObject("lifespans").entrySet()) {
                    LIFESPAN_SECONDS.put(UUID.fromString(entry.getKey()), entry.getValue().getAsLong());
                }
            }
            dirty = false;
        } catch (Exception exception) {
            DeathNoteMod.LOGGER.error("Could not load canon Death Note state", exception);
        }
    }

    public static void save(MinecraftServer server) {
        if (!dirty) return;
        JsonObject root = new JsonObject();
        if (ownerId != null) root.addProperty("ownerId", ownerId.toString());
        if (ownerName != null) root.addProperty("ownerName", ownerName);

        JsonObject eyes = new JsonObject();
        for (var entry : SHINIGAMI_EYES.entrySet()) eyes.addProperty(entry.getKey().toString(), entry.getValue());
        root.add("eyes", eyes);

        JsonObject lifespans = new JsonObject();
        for (var entry : LIFESPAN_SECONDS.entrySet()) lifespans.addProperty(entry.getKey().toString(), entry.getValue());
        root.add("lifespans", lifespans);

        Path path = path(server);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException exception) {
            DeathNoteMod.LOGGER.error("Could not save canon Death Note state", exception);
        }
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).resolve(FILE_NAME);
    }
}
