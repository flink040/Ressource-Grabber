package de.opsucht.resourcegrabber;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class AdvancedCustomItemsScreen extends Screen {
    private static final int CELL_SIZE = 36;

    private final List<CustomItemEntry> allItems;
    private List<CustomItemEntry> filteredItems;
    private CustomItemEntry selected;
    private TextFieldWidget searchField;
    private ButtonWidget previousButton;
    private ButtonWidget nextButton;
    private ButtonWidget categoryButton;
    private ButtonWidget favoritesButton;
    private ButtonWidget loreButton;
    private ButtonWidget idsButton;
    private ButtonWidget favoriteActionButton;
    private ButtonWidget giveButton;
    private ButtonWidget placeButton;
    private ButtonWidget hatButton;
    private boolean showLore = true;
    private boolean showIds = true;
    private boolean favoritesOnly;
    private boolean detailsVisible;
    private boolean layoutInitialized;
    private String searchQuery = "";
    private Category category = Category.ALL;
    private int page;
    private int columns;
    private int rows;
    private int pageSize;
    private int gridX;
    private int gridY;
    private int contentRight;
    private int detailX;
    private int knownItemCount;
    private int counterRefreshTicks;

    public AdvancedCustomItemsScreen(List<CustomItemEntry> items) {
        super(Text.translatable("screen.resourcegrabber.custom_items"));
        this.allItems = List.copyOf(items);
        this.filteredItems = this.allItems;
        this.selected = items.isEmpty() ? null : items.getFirst();
    }

    @Override
    protected void init() {
        if (!this.layoutInitialized) {
            this.detailsVisible = this.width >= 620;
            this.layoutInitialized = true;
        }
        this.layoutGrid();
        this.refreshKnownItemCount();

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.resourcegrabber.history_export"),
            button -> HistoryExchange.exportInteractive())
            .dimensions(8, 4, 58, 18).build());
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.resourcegrabber.history_import"), button -> {
                if (HistoryExchange.importInteractive()) {
                    this.refreshKnownItemCount();
                    this.refreshFilter();
                }
            }).dimensions(70, 4, 58, 18).build());

        int searchWidth = Math.max(100, this.contentRight - 16);
        this.searchField = new TextFieldWidget(this.textRenderer,
            8, 26, searchWidth, 18, Text.translatable("screen.resourcegrabber.search"));
        this.searchField.setPlaceholder(Text.translatable("screen.resourcegrabber.search"));
        this.searchField.setMaxLength(128);
        this.searchField.setText(this.searchQuery);
        this.searchField.setChangedListener(query -> {
            this.searchQuery = query;
            this.page = 0;
            this.refreshFilter();
        });
        this.addDrawableChild(this.searchField);

        int toolbarY = 48;
        int available = this.contentRight - 16;
        int categoryWidth = Math.max(72, available * 30 / 100);
        int remaining = available - categoryWidth - 12;
        int toggleWidth = Math.max(52, remaining / 3);
        this.categoryButton = this.addDrawableChild(ButtonWidget.builder(
            this.categoryText(), button -> {
                this.category = this.category.next();
                button.setMessage(this.categoryText());
                this.page = 0;
                this.refreshFilter();
            }).dimensions(8, toolbarY, categoryWidth, 18).build());
        int x = 12 + categoryWidth;
        this.favoritesButton = this.addDrawableChild(ButtonWidget.builder(
            this.favoritesText(), button -> {
                this.favoritesOnly = !this.favoritesOnly;
                button.setMessage(this.favoritesText());
                this.page = 0;
                this.refreshFilter();
            }).dimensions(x, toolbarY, toggleWidth, 18).build());
        x += toggleWidth + 4;
        this.loreButton = this.addDrawableChild(ButtonWidget.builder(
            this.toggleText("Lore", this.showLore), button -> {
                this.showLore = !this.showLore;
                button.setMessage(this.toggleText("Lore", this.showLore));
            }).dimensions(x, toolbarY, toggleWidth, 18).build());
        x += toggleWidth + 4;
        this.idsButton = this.addDrawableChild(ButtonWidget.builder(
            this.toggleText("IDs", this.showIds), button -> {
                this.showIds = !this.showIds;
                button.setMessage(this.toggleText("IDs", this.showIds));
            }).dimensions(x, toolbarY, Math.max(48, this.contentRight - x - 8), 18).build());

        int navY = this.height - 46;
        this.previousButton = this.addDrawableChild(ButtonWidget.builder(
            Text.literal("‹"), button -> {
                if (this.page > 0) {
                    this.page--;
                    this.updateButtons();
                }
            }).dimensions(8, navY, 28, 18).build());
        this.nextButton = this.addDrawableChild(ButtonWidget.builder(
            Text.literal("›"), button -> {
                if (this.page + 1 < this.pageCount()) {
                    this.page++;
                    this.updateButtons();
                }
            }).dimensions(this.contentRight - 36, navY, 28, 18).build());

        int actionY = this.height - 24;
        int actionArea = this.contentRight - 16;
        int gap = 3;
        int starWidth = 26;
        int normalWidth = Math.max(42, (actionArea - starWidth - gap * 4) / 4);
        x = 8;
        this.giveButton = this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.resourcegrabber.action_give"), button -> this.giveSelected())
            .dimensions(x, actionY, normalWidth, 18).build());
        x += normalWidth + gap;
        this.placeButton = this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.resourcegrabber.action_place"), button -> this.placeSelected())
            .dimensions(x, actionY, normalWidth, 18).build());
        x += normalWidth + gap;
        this.hatButton = this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.resourcegrabber.action_hat"), button -> this.hatSelected())
            .dimensions(x, actionY, normalWidth, 18).build());
        x += normalWidth + gap;
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.resourcegrabber.action_details"), button -> {
                this.detailsVisible = !this.detailsVisible;
                this.clearAndInit();
            }).dimensions(x, actionY, normalWidth, 18).build());
        x += normalWidth + gap;
        this.favoriteActionButton = this.addDrawableChild(ButtonWidget.builder(
            this.favoriteActionText(), button -> {
                if (this.selected != null) {
                    FavoriteItems.toggle(this.selected);
                    button.setMessage(this.favoriteActionText());
                    if (this.favoritesOnly) {
                        this.refreshFilter();
                    }
                }
            }).dimensions(x, actionY, starWidth, 18).build());
        this.refreshFilter();
    }

    private void layoutGrid() {
        int detailWidth = this.detailsVisible ? Math.min(196, Math.max(160, this.width / 3)) : 0;
        boolean overlayDetails = this.detailsVisible && this.width < 500;
        this.contentRight = overlayDetails ? this.width : this.width - detailWidth;
        this.detailX = overlayDetails ? this.width - detailWidth - 4 : this.contentRight + 4;
        int availableWidth = Math.max(CELL_SIZE * 3, this.contentRight - 16);
        this.columns = Math.max(3, Math.min(14, availableWidth / CELL_SIZE));
        this.rows = Math.max(1, (this.height - 124) / CELL_SIZE);
        this.pageSize = this.columns * this.rows;
        this.gridX = 8 + Math.max(0, (availableWidth - this.columns * CELL_SIZE) / 2);
        this.gridY = 70;
    }

    private void refreshFilter() {
        String query = this.searchQuery.strip().toLowerCase(Locale.ROOT);
        this.filteredItems = this.allItems.stream()
            .filter(entry -> this.category.matches(entry))
            .filter(entry -> !this.favoritesOnly || FavoriteItems.contains(entry))
            .filter(entry -> query.isEmpty() || matchesSearch(entry, query))
            .toList();
        if (this.selected == null || !this.filteredItems.contains(this.selected)) {
            this.selected = this.filteredItems.isEmpty() ? null : this.filteredItems.getFirst();
        }
        this.page = Math.min(this.page, this.pageCount() - 1);
        this.updateButtons();
    }

    private static boolean matchesSearch(CustomItemEntry entry, String query) {
        return entry.displayName().toLowerCase(Locale.ROOT).contains(query)
            || entry.modelId().toLowerCase(Locale.ROOT).contains(query)
            || entry.itemId().toLowerCase(Locale.ROOT).contains(query)
            || entry.formattedThreshold().contains(query)
            || entry.lore().stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(query))
            || entry.chestShopIds().stream()
                .anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(query));
    }

    private void updateButtons() {
        if (this.previousButton != null) {
            this.previousButton.active = this.page > 0;
        }
        if (this.nextButton != null) {
            this.nextButton.active = this.page + 1 < this.pageCount();
        }
        boolean active = this.selected != null;
        if (this.giveButton != null) {
            this.giveButton.active = active;
            this.placeButton.active = active;
            this.hatButton.active = active;
            this.favoriteActionButton.active = active;
            this.favoriteActionButton.setMessage(this.favoriteActionText());
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
        this.knownItemCount = LearnedItemNames.knownItemCount(CustomItemCatalog.sourceServer());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
            this.contentRight / 2, 8, 0xFFFFFF);
        int first = this.page * this.pageSize;
        CustomItemEntry hovered = null;
        ItemStack hoveredStack = ItemStack.EMPTY;
        for (int slot = 0; slot < this.pageSize && first + slot < this.filteredItems.size(); slot++) {
            CustomItemEntry entry = this.filteredItems.get(first + slot);
            int x = this.gridX + (slot % this.columns) * CELL_SIZE;
            int y = this.gridY + (slot / this.columns) * CELL_SIZE;
            boolean selectedEntry = entry.equals(this.selected);
            boolean hoveredEntry = mouseX >= x && mouseX < x + CELL_SIZE - 2
                && mouseY >= y && mouseY < y + CELL_SIZE - 2;
            int border = selectedEntry ? 0xFFE5B869 : hoveredEntry ? 0xFF9A6843 : 0xFF4A3022;
            context.fill(x, y, x + CELL_SIZE - 2, y + CELL_SIZE - 2, border);
            context.fill(x + 2, y + 2, x + CELL_SIZE - 4, y + CELL_SIZE - 4, 0xCC17100C);
            ItemStack stack = entry.createStack(this.showLore, this.showIds);
            context.drawItem(stack, x + 9, y + 3);
            String value = this.textRenderer.trimToWidth(entry.formattedThreshold(), CELL_SIZE - 6);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(value),
                x + (CELL_SIZE - 2) / 2, y + 24, 0xC8B49D);
            this.drawBadges(context, entry, x, y);
            if (hoveredEntry) {
                hovered = entry;
                hoveredStack = stack;
            }
        }
        if (this.detailsVisible) {
            this.renderDetails(context);
        }
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("screen.resourcegrabber.page_compact",
                this.page + 1, this.pageCount(), this.filteredItems.size()),
            this.contentRight / 2, this.height - 42, 0xC8B49D);
        if (ResourceGrabberClient.showLearningCounter()) {
            context.drawTextWithShadow(this.textRenderer,
                Text.translatable("screen.resourcegrabber.learned",
                    this.knownItemCount, this.allItems.size()),
                40, this.height - 42,
                LearnedItemNames.learnedRecently() ? 0x72E58B : 0x8E7D6B);
        }
        if (hovered != null) {
            context.drawItemTooltip(this.textRenderer, hoveredStack, mouseX, mouseY);
        }
    }

    private void drawBadges(DrawContext context, CustomItemEntry entry, int x, int y) {
        if (FavoriteItems.contains(entry)) {
            context.fill(x + 3, y + 3, x + 7, y + 7, 0xFFFFC857);
        }
        int badgeX = x + CELL_SIZE - 8;
        int badgeY = y + 3;
        if (entry.hasLearnedName()) {
            context.fill(badgeX, badgeY, badgeX + 4, badgeY + 4, 0xFF5FA8FF);
            badgeY += 5;
        }
        if (!entry.lore().isEmpty()) {
            context.fill(badgeX, badgeY, badgeX + 4, badgeY + 4, 0xFFBF75FF);
            badgeY += 5;
        }
        if (!entry.chestShopIds().isEmpty()) {
            context.fill(badgeX, badgeY, badgeX + 4, badgeY + 4, 0xFF60D394);
        }
    }

    private void renderDetails(DrawContext context) {
        context.fill(this.detailX, 4, this.width - 4, this.height - 4, 0xCC18110D);
        if (this.selected == null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("screen.resourcegrabber.no_selection"),
                (this.detailX + this.width) / 2, 24, 0xA99582);
            return;
        }
        int x = this.detailX + 8;
        int width = this.width - x - 8;
        context.drawItem(this.selected.createStack(this.showLore, this.showIds), x, 12);
        context.drawTextWithShadow(this.textRenderer,
            Text.literal(this.textRenderer.trimToWidth(this.selected.displayName(), width - 24)),
            x + 22, 16, 0xFFFFFF);
        int y = 38;
        y = this.detailLine(context, x, y, "Item", this.selected.itemId(), width);
        y = this.detailLine(context, x, y, "CMD", this.selected.formattedThreshold(), width);
        y = this.detailLine(context, x, y, "Index", Integer.toString(this.selected.floatIndex()), width);
        y = this.detailLine(context, x, y, "Model", this.selected.modelId(), width);
        y = this.detailLine(context, x, y,
            Text.translatable("screen.resourcegrabber.category_label").getString(),
            this.categoryFor(this.selected).label().getString(), width);
        y = this.detailLine(context, x, y, "Lore",
            Integer.toString(this.selected.lore().size()), width);
        y = this.detailLine(context, x, y, "Shop-IDs",
            Integer.toString(this.selected.chestShopIds().size()), width);
        if (!this.selected.lore().isEmpty() && y < this.height - 45) {
            context.drawTextWithShadow(this.textRenderer,
                Text.translatable("screen.resourcegrabber.lore_heading"), x, y + 4, 0xBF75FF);
            y += 16;
            for (String line : this.selected.lore()) {
                if (y >= this.height - 45) {
                    break;
                }
                context.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(line, width)), x, y, 0xC7B8AA);
                y += 11;
            }
        }
    }

    private int detailLine(
        DrawContext context, int x, int y, String label, String value, int width
    ) {
        context.drawTextWithShadow(this.textRenderer, Text.literal(label + ":"), x, y, 0xA99582);
        String trimmed = this.textRenderer.trimToWidth(value,
            Math.max(20, width - this.textRenderer.getWidth(label + ": ")));
        context.drawTextWithShadow(this.textRenderer, Text.literal(trimmed),
            x + this.textRenderer.getWidth(label + ": "), y, 0xE5D8CB);
        return y + 13;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) {
            return true;
        }
        if (click.button() != 0 && click.button() != 1) {
            return false;
        }
        if (click.x() < this.gridX || click.y() < this.gridY) {
            return false;
        }
        int column = ((int)click.x() - this.gridX) / CELL_SIZE;
        int row = ((int)click.y() - this.gridY) / CELL_SIZE;
        if (column < 0 || column >= this.columns || row < 0 || row >= this.rows) {
            return false;
        }
        int index = this.page * this.pageSize + row * this.columns + column;
        if (index < 0 || index >= this.filteredItems.size()) {
            return false;
        }
        this.selected = this.filteredItems.get(index);
        this.updateButtons();
        if (click.button() == 1) {
            return this.placeSelected();
        }
        if (doubled) {
            return this.giveSelected();
        }
        return true;
    }

    private boolean giveSelected() {
        if (this.selected == null) {
            return false;
        }
        MinecraftClient.getInstance().setScreen(
            new ChatScreen("/" + this.selected.giveCommand(), false));
        return true;
    }

    private boolean placeSelected() {
        return this.selected != null && PlacementController.begin(this.selected);
    }

    private boolean hatSelected() {
        return this.selected != null && PlacementController.equipAsHat(this.selected);
    }

    private Text categoryText() {
        return Text.translatable("screen.resourcegrabber.category", this.category.label());
    }

    private Text favoritesText() {
        return Text.translatable(this.favoritesOnly
            ? "screen.resourcegrabber.favorites_on"
            : "screen.resourcegrabber.favorites_off").formatted(
                this.favoritesOnly ? Formatting.GOLD : Formatting.GRAY);
    }

    private Text toggleText(String label, boolean enabled) {
        return Text.literal(label + " " + (enabled ? "✓" : "✕"))
            .formatted(enabled ? Formatting.GREEN : Formatting.GRAY);
    }

    private Text favoriteActionText() {
        return Text.literal(this.selected != null && FavoriteItems.contains(this.selected)
            ? "★" : "☆").formatted(Formatting.GOLD);
    }

    private Category categoryFor(CustomItemEntry entry) {
        for (Category value : Category.values()) {
            if (value != Category.ALL && value.matches(entry)) {
                return value;
            }
        }
        return Category.ALL;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private enum Category {
        ALL("all"),
        FAVORITES("favorites"),
        VEHICLES("vehicles"),
        FURNITURE("furniture"),
        HEADS("heads"),
        HORSE_ARMOR("horse_armor"),
        UNRECOGNIZED("unrecognized");

        private final String translationSuffix;

        Category(String translationSuffix) {
            this.translationSuffix = translationSuffix;
        }

        Text label() {
            return Text.translatable("screen.resourcegrabber.category." + this.translationSuffix);
        }

        Category next() {
            Category[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }

        boolean matches(CustomItemEntry entry) {
            String text = (entry.displayName() + ' ' + entry.modelId() + ' ' + entry.itemId())
                .toLowerCase(Locale.ROOT);
            return switch (this) {
                case ALL -> true;
                case FAVORITES -> FavoriteItems.contains(entry);
                case VEHICLES -> containsAny(text, "auto", "car", "vehicle", "truck",
                    "taxi", "bus", "bike", "motor", "van", "train", "boat", "plane");
                case FURNITURE -> containsAny(text, "chair", "table", "sofa", "couch",
                    "desk", "lamp", "shelf", "cabinet", "furniture", "stuhl", "tisch");
                case HEADS -> containsAny(text, "head", "skull", "hat", "helmet", "kopf");
                case HORSE_ARMOR -> entry.itemId().endsWith("golden_horse_armor");
                case UNRECOGNIZED -> !entry.hasLearnedName() && entry.lore().isEmpty();
            };
        }

        private static boolean containsAny(String value, String... needles) {
            for (String needle : needles) {
                if (value.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }
}
