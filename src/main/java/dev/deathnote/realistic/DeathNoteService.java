package dev.deathnote.realistic;

import dev.deathnote.realistic.network.DeathNoteWritePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DeathNoteService {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern VALID_REGISTRY_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final int ENTITY_RADIUS = 64;

    // Every block ID written in BLOCK mode remains active. The server keeps
    // erasing matching blocks from active areas as the world is played.
    private static final int GLOBAL_BLOCK_SCAN_RADIUS = 24;
    private static final int BLOCK_SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_BLOCKS_ERASED_PER_SCAN = 2048;

    private static final Map<UUID, PendingDeath> PENDING = new HashMap<>();
    private static final Map<UUID, Long> WRITER_COOLDOWN_UNTIL = new HashMap<>();
    private static final Set<String> CONDEMNED_BLOCK_IDS = new HashSet<>();
    private static long ticks;

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
        PENDING.put(target.getUUID(), new PendingDeath(target.getUUID(), writer.getUUID(), cause, dueTick));
        WRITER_COOLDOWN_UNTIL.put(writer.getUUID(), ticks + DeathNoteRules.WRITER_COOLDOWN_SECONDS * 20L);

        writer.sendSystemMessage(
            Component.translatable("message.deathnote_realistic.accepted", target.getName().getString(), DeathNoteRules.DEATH_DELAY_SECONDS)
                .withStyle(ChatFormatting.DARK_RED)
        );
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
        int erasedNow = eraseCondemnedBlocks(((ServerLevel) writer.level()).getServer(), MAX_BLOCKS_ERASED_PER_SCAN);

        writer.sendSystemMessage(Component.translatable(
            "message.deathnote_realistic.block_erasure_activated",
            id,
            erasedNow,
            GLOBAL_BLOCK_SCAN_RADIUS
        ).withStyle(ChatFormatting.DARK_RED));
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

                        var state = level.getBlockState(pos);
                        if (state.isAir()) continue;

                        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (!CONDEMNED_BLOCK_IDS.contains(blockId)) continue;

                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        erased++;
                        if (erased >= limit) break outer;
                    }
                }
            }
        }

        return erased;
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

        if (PENDING.isEmpty()) return;

        Iterator<Map.Entry<UUID, PendingDeath>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingDeath pending = iterator.next().getValue();
            if (pending.dueTick() > ticks) continue;

            ServerPlayer target = server.getPlayerList().getPlayer(pending.targetId());
            if (target != null && target.isAlive()) {
                target.sendSystemMessage(causeMessage(pending.cause()));
                target.kill((ServerLevel) target.level());
            }
            iterator.remove();
        }
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

    private record PendingDeath(UUID targetId, UUID writerId, DeathCause cause, long dueTick) {}
}
