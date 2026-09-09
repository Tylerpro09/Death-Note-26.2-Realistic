package dev.deathnote.realistic;

import dev.deathnote.realistic.network.DeathNoteWritePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DeathNoteService {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Map<UUID, PendingDeath> PENDING = new HashMap<>();
    private static final Map<UUID, Long> WRITER_COOLDOWN_UNTIL = new HashMap<>();
    private static long ticks;

    private DeathNoteService() {}

    public static void submit(ServerPlayer writer, DeathNoteWritePayload payload) {
        if (!isHoldingDeathNote(writer)) {
            message(writer, "message.deathnote_realistic.must_hold", ChatFormatting.RED);
            return;
        }

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

    public static void tick(MinecraftServer server) {
        ticks++;
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
