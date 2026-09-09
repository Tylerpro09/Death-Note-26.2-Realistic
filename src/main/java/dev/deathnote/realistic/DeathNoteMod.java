package dev.deathnote.realistic;

import dev.deathnote.realistic.network.DeathNoteWritePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
            CanonRouter.submit(context.player(), payload)
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DeathNoteService.loadState(server);
            CanonRulesState.load(server);
            CorruptEntityService.load(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DeathNoteService.shutdown(server);
            CanonRulesState.save(server);
            CorruptEntityService.save(server);
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
            DeathNoteService.enforceCondemnedPlayer(newPlayer)
        );
        ServerPlayerEvents.JOIN.register(DeathNoteService::enforceCondemnedPlayer);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DeathNoteService.tick(server);
            CanonRulesState.tick(server);
            CanonRulesState.save(server);
            CorruptEntityService.tick(server);
        });
        LOGGER.info("Death Note 26.2 Realistic v3 Canon Rules initialized.");
    }
}
