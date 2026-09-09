package dev.deathnote.realistic.network;

import dev.deathnote.realistic.DeathNoteMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DeathNoteWritePayload(String targetName, String causeId, String targetKind) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(DeathNoteMod.MOD_ID, "write_name");
    public static final Type<DeathNoteWritePayload> TYPE = new Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, DeathNoteWritePayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, DeathNoteWritePayload::targetName,
        ByteBufCodecs.STRING_UTF8, DeathNoteWritePayload::causeId,
        ByteBufCodecs.STRING_UTF8, DeathNoteWritePayload::targetKind,
        DeathNoteWritePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
