package dev.deathnote.realistic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Corrupt-mode entity logic.
 * Writing an entity type such as minecraft:chicken condemns that entity type
 * globally in active server areas. Newly loaded/spawned matching entities are
 * removed on subsequent scans until the server state is changed.
 */
public final class CorruptEntityService {
    private static final Pattern VALID_REGISTRY_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int SCAN_RADIUS = 96;
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_ERASED_PER_SCAN = 4096;

    private static final Set<String> CONDEMNED_ENTITY_IDS = new HashSet<>();
    private static boolean dirty;
    private static long ticks;

    private CorruptEntityService() {}

    public static void condemn(ServerPlayer writer, String rawId) {
        String id = normalizeRegistryId(rawId);
        if (id == null) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.invalid_registry_id").withStyle(ChatFormatting.RED));
            return;
        }

        CONDEMNED_ENTITY_IDS.add(id);
        dirty = true;
        int erasedNow = eraseMatching(((ServerLevel) writer.level()).getServer(), MAX_ERASED_PER_SCAN);
        writer.sendSystemMessage(Component.translatable(
            "message.deathnote_realistic.entity_condemned_global",
            id,
            erasedNow,
            SCAN_RADIUS
        ).withStyle(ChatFormatting.DARK_RED));
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (!CONDEMNED_ENTITY_IDS.isEmpty() && ticks % SCAN_INTERVAL_TICKS == 0L) {
            eraseMatching(server, MAX_ERASED_PER_SCAN);
        }
        if (dirty && ticks % 100L == 0L) {
            save(server);
        }
    }

    private static int eraseMatching(MinecraftServer server, int limit) {
        if (CONDEMNED_ENTITY_IDS.isEmpty()) return 0;

        int erased = 0;
        Set<java.util.UUID> visited = new HashSet<>();

        outer:
        for (ServerPlayer anchor : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) anchor.level();
            List<Entity> matches = level.getEntities(
                anchor,
                anchor.getBoundingBox().inflate(SCAN_RADIUS),
                entity -> !entity.isRemoved()
                    && !visited.contains(entity.getUUID())
                    && CONDEMNED_ENTITY_IDS.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
            );

            for (Entity entity : matches) {
                if (!visited.add(entity.getUUID())) continue;
                entity.discard();
                erased++;
                if (erased >= limit) break outer;
            }
        }

        return erased;
    }

    public static void load(MinecraftServer server) {
        CONDEMNED_ENTITY_IDS.clear();
        Path path = path(server);
        if (!Files.exists(path)) {
            dirty = false;
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("condemnedEntities")) {
                for (JsonElement element : root.getAsJsonArray("condemnedEntities")) {
                    String id = normalizeRegistryId(element.getAsString());
                    if (id != null) CONDEMNED_ENTITY_IDS.add(id);
                }
            }
            dirty = false;
        } catch (Exception exception) {
            DeathNoteMod.LOGGER.error("Could not load corrupt entity state", exception);
        }
    }

    public static void save(MinecraftServer server) {
        if (!dirty) return;
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        CONDEMNED_ENTITY_IDS.stream().sorted().forEach(array::add);
        root.add("condemnedEntities", array);

        Path path = path(server);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException exception) {
            DeathNoteMod.LOGGER.error("Could not save corrupt entity state", exception);
        }
    }

    private static String normalizeRegistryId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase();
        if (id.isEmpty()) return null;
        if (!id.contains(":")) id = "minecraft:" + id;
        return VALID_REGISTRY_ID.matcher(id).matches() ? id : null;
    }

    private static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
            .resolve("deathnote_realistic")
            .resolve("corrupt_entities.json");
    }
}
