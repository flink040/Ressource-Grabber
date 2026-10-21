package de.opsucht.resourcegrabber;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class PlacedModelEditorScreen extends Screen {
    private static final double POSITION_STEP = 0.1D;
    private static final float ROTATION_STEP = 15.0F;
    private static final float SCALE_STEP = 0.1F;

    private final UUID entityId;
    private final String modelName;
    private PlacementController.PlacementState state;

    PlacedModelEditorScreen(
        UUID entityId, String modelName, PlacementController.PlacementState state
    ) {
        super(Text.translatable("screen.resourcegrabber.model_editor"));
        this.entityId = entityId;
        this.modelName = modelName;
        this.state = state;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        this.addRow(48, "X", () -> this.move(-POSITION_STEP, 0, 0),
            () -> this.move(POSITION_STEP, 0, 0));
        this.addRow(74, "Y", () -> this.move(0, -POSITION_STEP, 0),
            () -> this.move(0, POSITION_STEP, 0));
        this.addRow(100, "Z", () -> this.move(0, 0, -POSITION_STEP),
            () -> this.move(0, 0, POSITION_STEP));
        this.addRow(126, "↻", () -> this.rotate(-ROTATION_STEP),
            () -> this.rotate(ROTATION_STEP));
        this.addRow(152, "⤢", () -> this.scale(-SCALE_STEP),
            () -> this.scale(SCALE_STEP));
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.resourcegrabber.model_remove"), button -> {
                PlacementController.remove(this.entityId);
                this.close();
            }).dimensions(center - 104, this.height - 30, 98, 20).build());
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.done"), button -> this.close())
            .dimensions(center + 6, this.height - 30, 98, 20).build());
    }

    private void addRow(int y, String symbol, Runnable minus, Runnable plus) {
        int center = this.width / 2;
        this.addDrawableChild(ButtonWidget.builder(Text.literal(symbol + " −"),
            button -> minus.run()).dimensions(center - 116, y, 54, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal(symbol + " +"),
            button -> plus.run()).dimensions(center + 62, y, 54, 20).build());
    }

    private void move(double x, double y, double z) {
        this.state = new PlacementController.PlacementState(
            this.state.x() + x, this.state.y() + y, this.state.z() + z,
            this.state.yaw(), this.state.scale());
        this.apply();
    }

    private void rotate(float amount) {
        this.state = new PlacementController.PlacementState(
            this.state.x(), this.state.y(), this.state.z(),
            this.state.yaw() + amount, this.state.scale());
        this.apply();
    }

    private void scale(float amount) {
        float newScale = Math.max(0.1F, Math.min(20.0F, this.state.scale() + amount));
        this.state = new PlacementController.PlacementState(
            this.state.x(), this.state.y(), this.state.z(),
            this.state.yaw(), newScale);
        this.apply();
    }

    private void apply() {
        PlacementController.update(this.entityId, this.state);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int center = this.width / 2;
        context.fill(center - 130, 8, center + 130, this.height - 4, 0xB0100B08);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
            center, 16, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.modelName),
            center, 29, 0xD8C6B2);
        this.drawValue(context, 55, format(this.state.x()));
        this.drawValue(context, 81, format(this.state.y()));
        this.drawValue(context, 107, format(this.state.z()));
        this.drawValue(context, 133, format(this.state.yaw()) + "°");
        this.drawValue(context, 159, format(this.state.scale()) + "×");
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("screen.resourcegrabber.model_editor_hint"),
            center, 180, 0xB9AA99);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawValue(DrawContext context, int y, String value) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(value),
            this.width / 2, y, 0xFFFFFF);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
