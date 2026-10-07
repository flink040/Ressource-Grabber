package de.opsucht.resourcegrabber;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public record CustomItemEntry(
    String itemId,
    int floatIndex,
    float threshold,
    String modelId
) {
    public ItemStack createStack() {
        return this.createStack(true, true);
    }

    public ItemStack createStack(boolean includeLore) {
        return this.createStack(includeLore, true);
    }

    public ItemStack createStack(boolean includeLore, boolean includeChestShopIds) {
        Identifier id = Identifier.tryParse(this.itemId);
        Item item = id == null
            ? Items.PAPER
            : Registries.ITEM.getOptionalValue(id).orElse(Items.PAPER);
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(
            this.floatValues(), List.of(), List.of(), List.of()));
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(
            this.displayName() + "  [CMD " + this.formattedThreshold() + "]"));
        List<String> tooltipLines = new ArrayList<>();
        if (includeLore) {
            tooltipLines.addAll(this.lore());
        }
        if (includeChestShopIds) {
            tooltipLines.addAll(this.chestShopIds());
        }
        if (!tooltipLines.isEmpty()) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(tooltipLines.stream()
                .<Text>map(Text::literal)
                .toList()));
        }
        return stack;
    }

    public String giveCommand() {
        return "give @s " + this.itemId
            + "[minecraft:custom_model_data={floats:["
            + this.floatValues().stream()
                .map(CustomItemEntry::formatFloat)
                .reduce((left, right) -> left + "," + right)
                .orElse("0")
            + "]}] 1";
    }

    public String displayName() {
        String learnedName = LearnedItemNames.find(CustomItemCatalog.sourceServer(),
            this.itemId, this.floatIndex, this.threshold).orElse(null);
        if (learnedName != null) {
            return learnedName;
        }
        String path = this.modelId;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            path = path.substring(slash + 1);
        }
        int colon = path.indexOf(':');
        if (colon >= 0 && colon + 1 < path.length()) {
            path = path.substring(colon + 1);
        }
        String[] words = path.replace('-', '_').split("_+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            result.append(word.substring(1));
        }
        return result.isEmpty() ? this.modelId : result.toString();
    }

    public String formattedThreshold() {
        return formatFloat(this.threshold);
    }

    public List<String> lore() {
        return LearnedItemNames.findLore(CustomItemCatalog.sourceServer(),
            this.itemId, this.floatIndex, this.threshold);
    }

    public List<String> chestShopIds() {
        return LearnedChestShopIds.tooltipLines(CustomItemCatalog.sourceServer(),
            this.itemId, this.floatIndex, this.threshold);
    }

    private List<Float> floatValues() {
        List<Float> values = new ArrayList<>();
        for (int index = 0; index <= this.floatIndex; index++) {
            values.add(0.0F);
        }
        values.set(this.floatIndex, this.threshold);
        return List.copyOf(values);
    }

    private static String formatFloat(float value) {
        if (!Float.isFinite(value)) {
            return "0";
        }
        return new BigDecimal(Float.toString(value)).stripTrailingZeros().toPlainString();
    }
}
