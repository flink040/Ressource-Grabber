package de.opsucht.resourcegrabber;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public final class CustomItemsScreen extends Screen {
    private static final int CELL_SIZE = 34;

    private final List<CustomItemEntry> allItems;
    private List<CustomItemEntry> filteredItems;
    private TextFieldWidget searchField;
    private ButtonWidget previousButton;
    private ButtonWidget nextButton;
    private ButtonWidget loreButton;
    private ButtonWidget chestShopIdButton;
    private boolean showLore = true;
    private boolean showChestShopIds = true;
    private int page;
    private int columns;
    private int rows;
    private int pageSize;
    private int gridX;
    private int gridY;
    private int knownItemCount;
    private int counterRefreshTicks;

    public CustomItemsScreen(List<CustomItemEntry> items) {
        super(Text.translatable("screen.resourcegrabber.custom_items"));
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
        this.refreshKnownItemCount();

        int toggleWidth = 90;
        int controlsWidth = Math.min(432, this.width - 24);
        int searchWidth = Math.max(100, controlsWidth - toggleWidth * 2 - 12);
        int controlsX = (this.width - searchWidth - toggleWidth * 2 - 12) / 2;
        this.searchField = new TextFieldWidget(this.textRenderer,
            controlsX, 25, searchWidth, 20,
            Text.translatable("screen.resourcegrabber.search"));
        this.searchField.setPlaceholder(Text.translatable("screen.resourcegrabber.search"));
        this.searchField.setMaxLength(128);
        this.searchField.setChangedListener(query -> {
            this.page = 0;
            this.refreshFilter(query);
        });
        this.addDrawableChild(this.searchField);
        this.loreButton = this.addDrawableChild(ButtonWidget.builder(
            this.loreButtonText(), button -> {
                this.showLore = !this.showLore;
                button.setMessage(this.loreButtonText());
            }).dimensions(controlsX + searchWidth + 6, 25, toggleWidth, 20).build());
        this.chestShopIdButton = this.addDrawableChild(ButtonWidget.builder(
            this.chestShopIdButtonText(), button -> {
                this.showChestShopIds = !this.showChestShopIds;
                button.setMessage(this.chestShopIdButtonText());
            }).dimensions(controlsX + searchWidth + toggleWidth + 12, 25,
                toggleWidth, 20).build());

        int buttonY = this.height - 28;
        this.previousButton = this.addDrawableChild(ButtonWidget.builder(
            Text.literal("<"), button -> {
                if (this.page > 0) {
                    this.page--;
                    this.updateButtons();
                }
            }).dimensions(this.width / 2 - 105, buttonY, 40, 20).build());
        this.nextButton = this.addDrawableChild(ButtonWidget.builder(
            Text.literal(">"), button -> {
                if (this.page + 1 < this.pageCount()) {
                    this.page++;
                    this.updateButtons();
                }
            }).dimensions(this.width / 2 + 65, buttonY, 40, 20).build());
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("gui.done"), button -> this.close())
            .dimensions(this.width / 2 - 50, buttonY, 100, 20).build());
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
                    || entry.chestShopIds().stream().anyMatch(line ->
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
    public void tick() {
        super.tick();
        if (++this.counterRefreshTicks >= 20) {
            this.counterRefreshTicks = 0;
            this.refreshKnownItemCount();
        }
    }

    private void refreshKnownItemCount() {
        this.knownItemCount = LearnedItemNames.knownItemCount(
            CustomItemCatalog.sourceServer());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
            this.width / 2, 7, 0xFFFFFF);

        int first = this.page * this.pageSize;
        CustomItemEntry hovered = null;
        ItemStack hoveredStack = ItemStack.EMPTY;
        for (int slot = 0; slot < this.pageSize && first + slot < this.filteredItems.size(); slot++) {
            CustomItemEntry entry = this.filteredItems.get(first + slot);
            int x = this.gridX + (slot % this.columns) * CELL_SIZE;
            int y = this.gridY + (slot / this.columns) * CELL_SIZE;
            boolean isHovered = mouseX >= x && mouseX < x + CELL_SIZE - 2
                && mouseY >= y && mouseY < y + CELL_SIZE - 2;
            context.fill(x, y, x + CELL_SIZE - 2, y + CELL_SIZE - 2,
                isHovered ? 0xCC8A5A35 : 0xAA2A1A12);
            context.fill(x + 1, y + 1, x + CELL_SIZE - 3, y + CELL_SIZE - 3,
                isHovered ? 0xCC3D271B : 0xAA17100C);
            ItemStack stack = entry.createStack(
                this.showLore, this.showChestShopIds);
            context.drawItem(stack, x + 8, y + 4);
            String value = entry.formattedThreshold();
            if (value.length() > 5) {
                value = value.substring(value.length() - 5);
            }
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(value),
                x + (CELL_SIZE - 2) / 2, y + 23, 0xC8B49D);
            if (isHovered) {
                hovered = entry;
                hoveredStack = stack;
            }
        }

        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("screen.resourcegrabber.page",
                this.page + 1, this.pageCount(), this.filteredItems.size()),
            this.width / 2, this.height - 41, 0xC8B49D);
        if (ResourceGrabberClient.showLearningCounter()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("screen.resourcegrabber.learned",
                    this.knownItemCount, this.allItems.size()),
                this.width / 2, this.height - 52,
                LearnedItemNames.learnedRecently() ? 0x72E58B : 0xC8B49D);
        }
        if (hovered != null) {
            context.drawItemTooltip(this.textRenderer, hoveredStack, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            return true;
        }
        if (click.button() != 0) {
            return false;
        }
        int column = ((int)click.x() - this.gridX) / CELL_SIZE;
        int row = ((int)click.y() - this.gridY) / CELL_SIZE;
        if (click.x() < this.gridX || click.y() < this.gridY
            || column < 0 || column >= this.columns || row < 0 || row >= this.rows) {
            return false;
        }
        int index = this.page * this.pageSize + row * this.columns + column;
        if (index < 0 || index >= this.filteredItems.size()) {
            return false;
        }
        CustomItemEntry entry = this.filteredItems.get(index);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(entry.giveCommand());
            return true;
        }
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private Text loreButtonText() {
        return Text.translatable(this.showLore
            ? "screen.resourcegrabber.lore_on"
            : "screen.resourcegrabber.lore_off");
    }

    private Text chestShopIdButtonText() {
        return Text.translatable(this.showChestShopIds
            ? "screen.resourcegrabber.chestshop_id_on"
            : "screen.resourcegrabber.chestshop_id_off");
    }
}
