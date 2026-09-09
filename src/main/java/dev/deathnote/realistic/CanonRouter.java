package dev.deathnote.realistic;

import dev.deathnote.realistic.network.DeathNoteWritePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class CanonRouter {
    private CanonRouter() {}

    public static void submit(ServerPlayer writer, DeathNoteWritePayload payload) {
        String kind = payload.targetKind() == null ? "player" : payload.targetKind().trim().toLowerCase();

        switch (kind) {
            case "player" -> submitCanonPlayer(writer, payload);
            case "transfer" -> transfer(writer, payload.targetName());
            case "eyes_deal" -> eyesDeal(writer);
            default -> DeathNoteService.submit(writer, payload);
        }
    }

    private static void submitCanonPlayer(ServerPlayer writer, DeathNoteWritePayload payload) {
        if (!isHoldingDeathNote(writer)) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.must_hold").withStyle(ChatFormatting.RED));
            return;
        }

        if (!CanonRulesState.ensureOwner(writer)) return;

        String name = payload.targetName() == null ? "" : payload.targetName().trim();
        ServerPlayer target = ((ServerLevel) writer.level()).getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.not_online").withStyle(ChatFormatting.RED));
            return;
        }

        if (!CanonRulesState.hasSeenRecently(writer, target, DeathNoteService.currentTick())) {
            writer.sendSystemMessage(Component.translatable(
                "message.deathnote_realistic.face_required",
                CanonRulesState.FACE_MEMORY_SECONDS
            ).withStyle(ChatFormatting.RED));
            return;
        }

        DeathNoteService.submit(writer, payload);
    }

    private static void transfer(ServerPlayer writer, String rawName) {
        if (!isHoldingDeathNote(writer)) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.must_hold").withStyle(ChatFormatting.RED));
            return;
        }

        String name = rawName == null ? "" : rawName.trim();
        ServerPlayer target = ((ServerLevel) writer.level()).getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.not_online").withStyle(ChatFormatting.RED));
            return;
        }

        CanonRulesState.transferOwnership(writer, target);
    }

    private static void eyesDeal(ServerPlayer writer) {
        if (!isHoldingDeathNote(writer)) {
            writer.sendSystemMessage(Component.translatable("message.deathnote_realistic.must_hold").withStyle(ChatFormatting.RED));
            return;
        }
        CanonRulesState.acceptShinigamiEyesDeal(writer);
    }

    private static boolean isHoldingDeathNote(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.DEATH_NOTE) || player.getOffhandItem().is(ModItems.DEATH_NOTE);
    }
}
