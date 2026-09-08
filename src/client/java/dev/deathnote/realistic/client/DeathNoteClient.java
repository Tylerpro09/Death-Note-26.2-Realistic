package dev.deathnote.realistic.client;

import dev.deathnote.realistic.ModItems;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public final class DeathNoteClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND && hand != InteractionHand.OFF_HAND) return InteractionResult.PASS;
            if (!player.getItemInHand(hand).is(ModItems.DEATH_NOTE)) return InteractionResult.PASS;

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new DeathNoteScreen(Component.translatable("screen.deathnote_realistic.title")));
            }
            return InteractionResult.SUCCESS;
        });
    }
}
