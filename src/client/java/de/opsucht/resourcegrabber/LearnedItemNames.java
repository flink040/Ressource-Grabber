package de.opsucht.resourcegrabber;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.slf4j.Logger;

public final class LearnedItemNames {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, Map<String, String>>>() { }
        .getType();
    private static final Path DATA_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("resourcegrabber-item-names.json");
    private static final Type LORE_DATA_TYPE = new TypeToken<Map<String, Map<String, List<String>>>>() { }
        .getType();
    private static final Path LORE_DATA_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("resourcegrabber-item-lore.json");

    private static final Map<String, Map<String, String>> namesByServer = new HashMap<>();
    private static final Map<String, Map<String, List<String>>> loreByServer = new HashMap<>();
    private static boolean dirty;
    private static long lastLearnedAtMillis;

    public static synchronized void load() {
        namesByServer.clear();
        loreByServer.clear();
        if (Files.isRegularFile(DATA_PATH)) {
            try (Reader reader = Files.newBufferedReader(DATA_PATH)) {
                Map<String, Map<String, String>> loaded = GSON.fromJson(reader, DATA_TYPE);
                if (loaded != null) {
                    loaded.forEach((server, names) ->
                        namesByServer.put(server, new HashMap<>(names)));
                }
                LOGGER.info("Loaded {} learned custom-item names", nameCount());
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Could not read learned custom-item names from {}", DATA_PATH, exception);
            }
        }
        if (Files.isRegularFile(LORE_DATA_PATH)) {
            try (Reader reader = Files.newBufferedReader(LORE_DATA_PATH)) {
                Map<String, Map<String, List<String>>> loaded = GSON.fromJson(reader, LORE_DATA_TYPE);
                if (loaded != null) {
                    loaded.forEach((server, lore) -> loreByServer.put(server, new HashMap<>(lore)));
                }
                LOGGER.info("Loaded {} learned custom-item lore entries", loreCount());
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Could not read learned custom-item lore from {}", LORE_DATA_PATH, exception);
            }
        }
    }

    public static synchronized LearningResult observe(String serverName, ItemStack stack) {
        return observe(serverName, stack, null);
    }

    public static synchronized LearningResult observe(
        String serverName, ItemStack stack, String fallbackName
    ) {
        if (stack.isEmpty()) {
            return LearningResult.NONE;
        }
        CustomModelDataComponent modelData = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (modelData == null || modelData.floats().isEmpty()) {
            return LearningResult.NONE;
        }

        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        if ((name == null || name.getString().isBlank())
            && fallbackName != null && !fallbackName.isBlank()) {
            name = Text.literal(fallbackName.strip());
        }
        if (name == null) {
            name = stack.get(DataComponentTypes.ITEM_NAME);
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        String displayName = name == null ? "" : name.getString().strip();
        Map<String, String> serverNames = namesByServer.computeIfAbsent(
            serverName, ignored -> new HashMap<>());
        List<String> lore = readLore(stack);
        Map<String, List<String>> serverLore = loreByServer.computeIfAbsent(
            serverName, ignored -> new HashMap<>());
        List<Float> floats = modelData.floats();
        int newlyRecognized = 0;
        for (int index = 0; index < floats.size(); index++) {
            float value = floats.get(index);
            if (!CustomItemCatalog.contains(itemId, index, value)) {
                continue;
            }
            String itemKey = key(itemId, index, value);
            boolean wasKnown = serverNames.containsKey(itemKey)
                || serverLore.containsKey(itemKey);
            String previous = displayName.isBlank() ? null
                : serverNames.put(itemKey, displayName);
            if (!displayName.isBlank() && !displayName.equals(previous)) {
                dirty = true;
                LOGGER.info("Learned custom-item name '{}' for {} CMD[{}]={}",
                    displayName, itemId, index, value);
            }
            if (!lore.isEmpty() && !lore.equals(serverLore.put(itemKey, lore))) {
                dirty = true;
                LOGGER.info("Learned {} lore lines for {} CMD[{}]={}",
                    lore.size(), itemId, index, value);
            }
            if (!wasKnown && (serverNames.containsKey(itemKey)
                || serverLore.containsKey(itemKey))) {
                newlyRecognized++;
            }
        }
        if (newlyRecognized > 0) {
            lastLearnedAtMillis = System.currentTimeMillis();
        }
        return newlyRecognized == 0 ? LearningResult.NONE
            : new LearningResult(newlyRecognized, displayName);
    }

    public static synchronized Optional<String> find(
        String serverName, String itemId, int floatIndex, float threshold
    ) {
        Map<String, String> serverNames = namesByServer.get(serverName);
        if (serverNames == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(serverNames.get(key(itemId, floatIndex, threshold)));
    }

    public static synchronized List<String> findLore(
        String serverName, String itemId, int floatIndex, float threshold
    ) {
        Map<String, List<String>> serverLore = loreByServer.get(serverName);
        if (serverLore == null) {
            return List.of();
        }
        return serverLore.getOrDefault(key(itemId, floatIndex, threshold), List.of());
    }

    public static synchronized int knownItemCount(String serverName) {
        Map<String, String> names = namesByServer.getOrDefault(serverName, Map.of());
        Map<String, List<String>> lore = loreByServer.getOrDefault(serverName, Map.of());
        HashSet<String> known = new HashSet<>(names.keySet());
        known.addAll(lore.keySet());
        int count = 0;
        for (CustomItemEntry entry : CustomItemCatalog.snapshot()) {
            if (known.contains(key(entry.itemId(), entry.floatIndex(), entry.threshold()))) {
                count++;
            }
        }
        return count;
    }

    public static synchronized boolean learnedRecently() {
        return System.currentTimeMillis() - lastLearnedAtMillis < 3000L;
    }

    public static synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Path temporary = Files.createTempFile(DATA_PATH.getParent(),
                ".resourcegrabber-item-names-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    GSON.toJson(namesByServer, DATA_TYPE, writer);
                }
                Files.move(temporary, DATA_PATH, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            Path loreTemporary = Files.createTempFile(LORE_DATA_PATH.getParent(),
                ".resourcegrabber-item-lore-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(loreTemporary)) {
                    GSON.toJson(loreByServer, LORE_DATA_TYPE, writer);
                }
                Files.move(loreTemporary, LORE_DATA_PATH, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(loreTemporary);
            }
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save learned custom-item names to {}", DATA_PATH, exception);
        }
    }

    private static String key(String itemId, int floatIndex, float value) {
        return itemId + '|' + floatIndex + '|' + Integer.toHexString(Float.floatToIntBits(value));
    }

    private static List<String> readLore(ItemStack stack) {
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore == null || lore.lines().isEmpty()) {
            return List.of();
        }
        return lore.lines().stream()
            .map(Text::getString)
            .map(String::stripTrailing)
            .filter(line -> !line.isBlank())
            .toList();
    }

    private static int nameCount() {
        return namesByServer.values().stream().mapToInt(Map::size).sum();
    }

    private static int loreCount() {
        return loreByServer.values().stream().mapToInt(Map::size).sum();
    }

    public record LearningResult(int count, String latestName) {
        public static final LearningResult NONE = new LearningResult(0, "");

        public LearningResult merge(LearningResult other) {
            if (other.count == 0) {
                return this;
            }
            return new LearningResult(this.count + other.count, other.latestName);
        }
    }

    private LearnedItemNames() {
    }
}
