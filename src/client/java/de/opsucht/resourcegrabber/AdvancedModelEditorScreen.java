package de.opsucht.resourcegrabber;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class AdvancedModelEditorScreen extends Screen {
    private static final double[] POSITION_STEPS = {0.01D, 0.1D, 0.5D, 1.0D};
    private static final float[] ROTATION_STEPS = {1.0F, 5.0F, 15.0F, 45.0F};
    private static final float[] SCALE_STEPS = {0.01F, 0.1F, 0.5F, 1.0F};

    private final UUID entityId;
    private final String displayName;
    private final ItemStack itemStack;
    private final PlacementController.PlacementState initialState;
    private final Deque<PlacementController.PlacementState> undo = new ArrayDeque<>();
    private final EditBox[] fields = new EditBox[7];
    private PlacementController.PlacementState state;
    private Button positionStepButton;
    private Button rotationStepButton;
    private Button scaleStepButton;
    private int positionStepIndex = 1;
    private int rotationStepIndex = 2;
    private int scaleStepIndex = 1;
    private int panelX;
    private int panelWidth;
    private boolean syncingFields;

    AdvancedModelEditorScreen(
        UUID entityId, CustomItemEntry entry, PlacementController.PlacementState state
    ) {
        this(entityId, entry.displayName(), entry.createStack(false, false), state);
    }

    AdvancedModelEditorScreen(
        UUID entityId,
        String displayName,
        ItemStack itemStack,
        PlacementController.PlacementState state
    ) {
        super(Component.translatable("screen.resourcegrabber.model_editor"));
        this.entityId = entityId;
        this.displayName = displayName;
        this.itemStack = itemStack.copy();
        this.state = state;
        this.initialState = state;
    }

    @Override
    protected void init() {
        this.panelWidth = Math.min(230, this.width - 16);
        this.panelX = this.width - this.panelWidth - 8;
        int rowY = 32;
        for (int index = 0; index < this.fields.length; index++) {
            final int fieldIndex = index;
            this.addRenderableWidget(Button.builder(Component.literal("−"),
                button -> this.adjust(fieldIndex, -1.0D))
                .bounds(this.panelX + 26, rowY, 24, 16).build());
            EditBox field = new EditBox(this.font,
                this.panelX + 54, rowY, this.panelWidth - 108, 16, Component.empty());
            field.setMaxLength(20);
            field.setResponder(value -> this.readFields());
            this.fields[index] = this.addRenderableWidget(field);
            this.addRenderableWidget(Button.builder(Component.literal("+"),
                button -> this.adjust(fieldIndex, 1.0D))
                .bounds(this.panelX + this.panelWidth - 50, rowY, 24, 16).build());
            rowY += 18;
        }

        int stepsY = this.height - 84;
        int third = (this.panelWidth - 20) / 3;
        this.positionStepButton = this.addRenderableWidget(Button.builder(
            this.positionStepText(), button -> {
                this.positionStepIndex = (this.positionStepIndex + 1) % POSITION_STEPS.length;
                button.setMessage(this.positionStepText());
            }).bounds(this.panelX + 6, stepsY, third, 16).build());
        this.rotationStepButton = this.addRenderableWidget(Button.builder(
            this.rotationStepText(), button -> {
                this.rotationStepIndex = (this.rotationStepIndex + 1) % ROTATION_STEPS.length;
                button.setMessage(this.rotationStepText());
            }).bounds(this.panelX + 10 + third, stepsY, third, 16).build());
        this.scaleStepButton = this.addRenderableWidget(Button.builder(
            this.scaleStepText(), button -> {
                this.scaleStepIndex = (this.scaleStepIndex + 1) % SCALE_STEPS.length;
                button.setMessage(this.scaleStepText());
            }).bounds(this.panelX + 14 + third * 2, stepsY, third, 16).build());

        int actionY = this.height - 64;
        this.addSmallAction(Component.translatable("screen.resourcegrabber.undo"), 0, actionY,
            this::undo);
        this.addSmallAction(Component.translatable("screen.resourcegrabber.reset"), 1, actionY,
            () -> this.apply(this.initialState, true, true));
        this.addSmallAction(Component.translatable("screen.resourcegrabber.duplicate"), 2, actionY,
            () -> PlacementController.duplicate(this.itemStack, this.state));

        int alignY = this.height - 44;
        int half = (this.panelWidth - 16) / 2;
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.resourcegrabber.align_player"), button -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    this.apply(new PlacementController.PlacementState(
                        this.state.x(), this.state.y(), this.state.z(),
                        this.state.rotationX(), client.player.getYRot(),
                        this.state.rotationZ(), this.state.scale()), true, true);
                }
            }).bounds(this.panelX + 6, alignY, half, 16).build());
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.resourcegrabber.center_block"), button ->
                this.apply(new PlacementController.PlacementState(
                    Math.floor(this.state.x()) + 0.5D, this.state.y(),
                    Math.floor(this.state.z()) + 0.5D, this.state.rotationX(),
                    this.state.rotationY(), this.state.rotationZ(), this.state.scale()),
                    true, true))
            .bounds(this.panelX + 10 + half, alignY, half, 16).build());

        int bottomY = this.height - 24;
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.resourcegrabber.model_remove"), button -> {
                PlacementController.remove(this.entityId);
                this.onClose();
            }).bounds(this.panelX + 6, bottomY, half, 16).build());
        this.addRenderableWidget(Button.builder(
            Component.translatable("gui.done"), button -> this.onClose())
            .bounds(this.panelX + 10 + half, bottomY, half, 16).build());
        this.syncFields();
    }

    private void addSmallAction(Component text, int column, int y, Runnable action) {
        int third = (this.panelWidth - 20) / 3;
        this.addRenderableWidget(Button.builder(text, button -> action.run())
            .bounds(this.panelX + 6 + column * (third + 4), y, third, 16).build());
    }

    private void adjust(int index, double direction) {
        double amount = switch (index) {
            case 0, 1, 2 -> POSITION_STEPS[this.positionStepIndex];
            case 3, 4, 5 -> ROTATION_STEPS[this.rotationStepIndex];
            default -> SCALE_STEPS[this.scaleStepIndex];
        } * direction;
        PlacementController.PlacementState next = switch (index) {
            case 0 -> withPosition(value -> value + amount, null, null);
            case 1 -> withPosition(null, value -> value + amount, null);
            case 2 -> withPosition(null, null, value -> value + amount);
            case 3 -> withRotation((float)(this.state.rotationX() + amount),
                this.state.rotationY(), this.state.rotationZ());
            case 4 -> withRotation(this.state.rotationX(),
                (float)(this.state.rotationY() + amount), this.state.rotationZ());
            case 5 -> withRotation(this.state.rotationX(), this.state.rotationY(),
                (float)(this.state.rotationZ() + amount));
            default -> new PlacementController.PlacementState(
                this.state.x(), this.state.y(), this.state.z(), this.state.rotationX(),
                this.state.rotationY(), this.state.rotationZ(),
                Math.max(0.01F, Math.min(20.0F, (float)(this.state.scale() + amount))));
        };
        this.apply(next, true, true);
    }

    private PlacementController.PlacementState withPosition(
        DoubleUnaryOperator x, DoubleUnaryOperator y, DoubleUnaryOperator z
    ) {
        return new PlacementController.PlacementState(
            x == null ? this.state.x() : x.applyAsDouble(this.state.x()),
            y == null ? this.state.y() : y.applyAsDouble(this.state.y()),
            z == null ? this.state.z() : z.applyAsDouble(this.state.z()),
            this.state.rotationX(), this.state.rotationY(),
            this.state.rotationZ(), this.state.scale());
    }

    private PlacementController.PlacementState withRotation(float x, float y, float z) {
        return new PlacementController.PlacementState(this.state.x(), this.state.y(),
            this.state.z(), x, y, z, this.state.scale());
    }

    private void readFields() {
        if (this.syncingFields) {
            return;
        }
        double[] values = new double[this.fields.length];
        try {
            for (int index = 0; index < this.fields.length; index++) {
                values[index] = Double.parseDouble(this.fields[index].getValue().replace(',', '.'));
            }
        } catch (NumberFormatException ignored) {
            return;
        }
        PlacementController.PlacementState next = new PlacementController.PlacementState(
            values[0], values[1], values[2], (float)values[3], (float)values[4],
            (float)values[5], Math.max(0.01F, Math.min(20.0F, (float)values[6])));
        this.apply(next, true, false);
    }

    private void undo() {
        if (!this.undo.isEmpty()) {
            this.state = this.undo.removeLast();
            PlacementController.update(this.entityId, this.state);
            this.syncFields();
        }
    }

    private void apply(
        PlacementController.PlacementState next, boolean recordUndo, boolean sync
    ) {
        if (recordUndo && !next.equals(this.state)) {
            this.undo.addLast(this.state);
            while (this.undo.size() > 100) {
                this.undo.removeFirst();
            }
        }
        this.state = next;
        PlacementController.update(this.entityId, next);
        if (sync) {
            this.syncFields();
        }
    }

    private void syncFields() {
        this.syncingFields = true;
        double[] values = {this.state.x(), this.state.y(), this.state.z(),
            this.state.rotationX(), this.state.rotationY(), this.state.rotationZ(),
            this.state.scale()};
        for (int index = 0; index < this.fields.length; index++) {
            this.fields[index].setValue(format(values[index]));
        }
        this.syncingFields = false;
    }

    @Override
    public void extractRenderState(
        GuiGraphicsExtractor context, int mouseX, int mouseY, float delta
    ) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.fill(this.panelX - 4, 4, this.width - 4, this.height - 4, 0xC018120E);
        context.centeredText(this.font, this.title,
            this.panelX + this.panelWidth / 2, 8, 0xFFFFFF);
        String name = this.font.plainSubstrByWidth(this.displayName,
            this.panelWidth - 20);
        context.centeredText(this.font, Component.literal(name),
            this.panelX + this.panelWidth / 2, 20, 0xD8C6B2);
        String[] labels = {"X", "Y", "Z", "R-X", "R-Y", "R-Z", "S"};
        for (int index = 0; index < labels.length; index++) {
            context.text(this.font, Component.literal(labels[index]),
                this.panelX + 8, 36 + index * 18, 0xC8B49D, true);
        }
    }

    private Component positionStepText() {
        return Component.literal("P " + format(POSITION_STEPS[this.positionStepIndex]));
    }

    private Component rotationStepText() {
        return Component.literal("R " + format(ROTATION_STEPS[this.rotationStepIndex]) + "°");
    }

    private Component scaleStepText() {
        return Component.literal("S " + format(SCALE_STEPS[this.scaleStepIndex]));
    }

    private static String format(double value) {
        String formatted = String.format(Locale.ROOT, "%.2f", value);
        return formatted.replaceAll("\\.?0+$", "");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
