package de.opsucht.resourcegrabber;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class CustomItemsScreen extends Screen {
    private static final int CELL_SIZE = 34;

    private final List<CustomItemEntry> allItems;
    private List<CustomItemEntry> filteredItems;
    private EditBox searchField;
    private Button previousButton;
    private Button nextButton;
    private int page;
    private int columns;
    private int rows;
    private int pageSize;
    private int gridX;
    private int gridY;

    public CustomItemsScreen(List<CustomItemEntry> items) {
        super(Component.translatable("screen.resourcegrabber.custom_items"));
        this.allItems = List.copyOf(items);
        this.filteredItems = this.allItems;
    }

    @Override
    protected void init() {
        this.columns = Math.max(4, Math.min(12, (this.width - 24) / CELL_SIZE));
        this.rows = Math.max(2, (this.height - 112) / CELL_SIZE);
        this.pageSize = this.columns * this.rows;
        this.gridX = (this.width - this.columns * CELL_SIZE) / 2;
        this.gridY = 52;

        this.searchField = new EditBox(this.font,
            this.width / 2 - 120, 25, 240, 20,
            Component.translatable("screen.resourcegrabber.search"));
        this.searchField.setHint(Component.translatable("screen.resourcegrabber.search"));
        this.searchField.setMaxLength(128);
        this.searchField.setResponder(query -> {
            this.page = 0;
            this.refreshFilter(query);
        });
        this.addRenderableWidget(this.searchField);

        int buttonY = this.height - 28;
        this.previousButton = this.addRenderableWidget(Button.builder(
            Component.literal("<"), button -> {
                if (this.page > 0) {
                    this.page--;
                    this.updateButtons();
                }
            }).bounds(this.width / 2 - 105, buttonY, 40, 20).build());
        this.nextButton = this.addRenderableWidget(Button.builder(
            Component.literal(">"), button -> {
                if (this.page + 1 < this.pageCount()) {
                    this.page++;
                    this.updateButtons();
                }
            }).bounds(this.width / 2 + 65, buttonY, 40, 20).build());
        this.addRenderableWidget(Button.builder(
            Component.translatable("gui.done"), button -> this.onClose())
            .bounds(this.width / 2 - 50, buttonY, 100, 20).build());
        this.updateButtons();
    }

    private void refreshFilter(String query) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            this.filteredItems = this.allItems;
        } else {
            this.filteredItems = this.allItems.stream()
                .filter(entry -> entry.displayName().toLowerCase(Locale.ROOT).contains(normalized)
                    || entry.lore().stream().anyMatch(line ->
                        line.toLowerCase(Locale.ROOT).contains(normalized))
                    || entry.modelId().toLowerCase(Locale.ROOT).contains(normalized)
                    || entry.itemId().toLowerCase(Locale.ROOT).contains(normalized)
                    || entry.formattedThreshold().contains(normalized))
                .toList();
        }
        this.updateButtons();
    }

    private void updateButtons() {
        if (this.previousButton != null) {
            this.previousButton.active = this.page > 0;
        }
        if (this.nextButton != null) {
            this.nextButton.active = this.page + 1 < this.pageCount();
        }
    }

    private int pageCount() {
        return Math.max(1, (this.filteredItems.size() + this.pageSize - 1) / this.pageSize);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title,
            this.width / 2, 7, 0xFFFFFFFF);

        int first = this.page * this.pageSize;
        CustomItemEntry hovered = null;
        ItemStack hoveredStack = ItemStack.EMPTY;
        for (int slot = 0; slot < this.pageSize && first + slot < this.filteredItems.size(); slot++) {
            CustomItemEntry entry = this.filteredItems.get(first + slot);
            int x = this.gridX + (slot % this.columns) * CELL_SIZE;
            int y = this.gridY + (slot / this.columns) * CELL_SIZE;
            boolean isHovered = mouseX >= x && mouseX < x + CELL_SIZE - 2
                && mouseY >= y && mouseY < y + CELL_SIZE - 2;
            graphics.fill(x, y, x + CELL_SIZE - 2, y + CELL_SIZE - 2,
                isHovered ? 0xCC8A5A35 : 0xAA2A1A12);
            graphics.fill(x + 1, y + 1, x + CELL_SIZE - 3, y + CELL_SIZE - 3,
                isHovered ? 0xCC3D271B : 0xAA17100C);
            ItemStack stack = entry.createStack();
            graphics.item(stack, x + 8, y + 4);
            String value = entry.formattedThreshold();
            if (value.length() > 5) {
                value = value.substring(value.length() - 5);
            }
            graphics.centeredText(this.font, Component.literal(value),
                x + (CELL_SIZE - 2) / 2, y + 23, 0xFFC8B49D);
            if (isHovered) {
                hovered = entry;
                hoveredStack = stack;
            }
        }

        graphics.centeredText(this.font,
            Component.translatable("screen.resourcegrabber.page",
                this.page + 1, this.pageCount(), this.filteredItems.size()),
            this.width / 2, this.height - 41, 0xFFC8B49D);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(this.font, hoveredStack, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        int column = ((int)event.x() - this.gridX) / CELL_SIZE;
        int row = ((int)event.y() - this.gridY) / CELL_SIZE;
        if (event.x() < this.gridX || event.y() < this.gridY
            || column < 0 || column >= this.columns || row < 0 || row >= this.rows) {
            return false;
        }
        int index = this.page * this.pageSize + row * this.columns + column;
        if (index < 0 || index >= this.filteredItems.size()) {
            return false;
        }
        CustomItemEntry entry = this.filteredItems.get(index);
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) {
            client.getConnection().sendCommand(entry.giveCommand());
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
