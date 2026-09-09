package dev.deathnote.realistic.client;

import dev.deathnote.realistic.DeathCause;
import dev.deathnote.realistic.DeathNoteRules;
import dev.deathnote.realistic.network.DeathNoteWritePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DeathNoteScreen extends Screen {
    private EditBox targetBox;
    private DeathCause cause = DeathCause.HEART_ATTACK;
    private String targetKind = "player";
    private Button causeButton;
    private Button targetKindButton;

    public DeathNoteScreen(Component title) {
        super(title);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = Math.max(45, this.height / 2 - 86);

        this.targetBox = new EditBox(this.font, centerX - 100, top, 200, 20, Component.translatable("screen.deathnote_realistic.target"));
        this.targetBox.setMaxLength(64);
        updateHint();
        this.addRenderableWidget(this.targetBox);
        this.setInitialFocus(this.targetBox);

        this.targetKindButton = Button.builder(targetKindLabel(), button -> {
            this.targetKind = switch (this.targetKind) {
                case "player" -> "entity";
                case "entity" -> "block";
                default -> "player";
            };
            button.setMessage(targetKindLabel());
            updateHint();
        }).bounds(centerX - 100, top + 32, 200, 20).build();
        this.addRenderableWidget(this.targetKindButton);

        this.causeButton = Button.builder(causeLabel(), button -> {
            this.cause = switch (this.cause) {
                case HEART_ATTACK -> DeathCause.ACCIDENT;
                case ACCIDENT -> DeathCause.MYSTERIOUS;
                case MYSTERIOUS -> DeathCause.HEART_ATTACK;
            };
            button.setMessage(causeLabel());
        }).bounds(centerX - 100, top + 64, 200, 20).build();
        this.addRenderableWidget(this.causeButton);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.deathnote_realistic.write"), button -> submit())
            .bounds(centerX - 100, top + 96, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
            .bounds(centerX + 2, top + 96, 98, 20).build());
    }

    private Component causeLabel() {
        return Component.translatable("screen.deathnote_realistic.cause." + this.cause.id());
    }

    private Component targetKindLabel() {
        return Component.translatable("screen.deathnote_realistic.target_kind." + this.targetKind);
    }

    private void updateHint() {
        if (this.targetBox != null) {
            this.targetBox.setHint(Component.translatable("screen.deathnote_realistic.target_hint." + this.targetKind));
        }
    }

    private void submit() {
        String target = this.targetBox.getValue().trim();
        if (target.isEmpty()) return;
        ClientPlayNetworking.send(new DeathNoteWritePayload(target, this.cause.id(), this.targetKind));
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int centerX = this.width / 2;
        int top = Math.max(45, this.height / 2 - 86);
        graphics.text(this.font, this.title, centerX - this.font.width(this.title) / 2, top - 30, 0xFFAA0000, true);
        graphics.text(this.font, Component.translatable("screen.deathnote_realistic.rule", DeathNoteRules.DEATH_DELAY_SECONDS), centerX - 100, top - 14, 0xFFB0B0B0, false);
    }
}
