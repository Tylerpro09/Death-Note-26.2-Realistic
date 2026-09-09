package dev.deathnote.realistic;

import dev.deathnote.realistic.network.DeathNoteWritePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeathNoteMod implements ModInitializer {
    public static final String MOD_ID = "deathnote_realistic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModItems.initialize();

        PayloadTypeRegistry.serverboundPlay().register(DeathNoteWritePayload.TYPE, DeathNoteWritePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DeathNoteWritePayload.TYPE, (payload, context) ->
            DeathNoteService.submit(context.player(), payload)
        );

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
            DeathNoteService.enforceCondemnedPlayer(newPlayer)
        );
        ServerPlayerEvents.JOIN.register(DeathNoteService::enforceCondemnedPlayer);

        ServerTickEvents.END_SERVER_TICK.register(DeathNoteService::tick);
        LOGGER.info("Death Note 26.2 Realistic initialized.");
    }
}
