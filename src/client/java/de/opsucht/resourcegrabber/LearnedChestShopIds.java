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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import org.slf4j.Logger;

public final class LearnedChestShopIds {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<
        Map<String, Map<String, Map<String, List<String>>>>>() { }.getType();
    private static final Path DATA_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("resourcegrabber-chestshop-ids.json");
    private static final Map<String, Map<String, Map<String, List<String>>>> idsByServer
        = new TreeMap<>();
    private static boolean dirty;

    public static synchronized void load() {
        idsByServer.clear();
        if (!Files.isRegularFile(DATA_PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(DATA_PATH)) {
            Map<String, Map<String, Map<String, List<String>>>> loaded =
                GSON.fromJson(reader, DATA_TYPE);
            if (loaded != null) {
                idsByServer.putAll(loaded);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not read learned ChestShop IDs from {}", DATA_PATH, exception);
        }
    }

    public static synchronized void observe(
        String serverName, String worldScope, ItemStack stack, String chestShopId
    ) {
        if (stack.isEmpty() || chestShopId == null || chestShopId.isBlank()) {
            return;
        }
        CustomModelData modelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if (modelData == null || modelData.floats().isEmpty()) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Map<String, List<String>> worldIds = idsByServer
            .computeIfAbsent(serverName, ignored -> new TreeMap<>())
            .computeIfAbsent(worldScope, ignored -> new TreeMap<>());
        for (int index = 0; index < modelData.floats().size(); index++) {
            float value = modelData.floats().get(index);
            if (!CustomItemCatalog.contains(itemId, index, value)) {
                continue;
            }
            List<String> ids = worldIds.computeIfAbsent(
                key(itemId, index, value), ignored -> new ArrayList<>());
            String normalizedId = chestShopId.strip();
            if (!ids.contains(normalizedId)) {
                ids.add(normalizedId);
                dirty = true;
                LOGGER.info("Learned ChestShop ID '{}' for {} CMD[{}]={} in {}",
                    normalizedId, itemId, index, value, worldScope);
            }
        }
    }

    public static synchronized List<String> tooltipLines(
        String serverName, String itemId, int floatIndex, float threshold
    ) {
        Map<String, Map<String, List<String>>> serverIds = idsByServer.get(serverName);
        if (serverIds == null) {
            return List.of();
        }
        String itemKey = key(itemId, floatIndex, threshold);
        String currentScope = currentWorldScope();
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        List<String> currentIds = serverIds.getOrDefault(currentScope, Map.of())
            .getOrDefault(itemKey, List.of());
        currentIds.forEach(id -> lines.add("ChestShop-ID: " + id));
        serverIds.forEach((scope, items) -> {
            if (scope.equals(currentScope)) {
                return;
            }
            for (String id : items.getOrDefault(itemKey, List.of())) {
                lines.add("ChestShop-ID [" + scope + "]: " + id);
            }
        });
        return List.copyOf(lines);
    }

    public static String currentWorldScope() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "unknown";
        }
        String dimension = client.level.dimension().identifier().toString();
        Objective sidebar = client.level.getScoreboard()
            .getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null || sidebar.getDisplayName().getString().isBlank()) {
            return dimension;
        }
        return sidebar.getDisplayName().getString().strip() + " | " + dimension;
    }

    public static synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Path temporary = Files.createTempFile(DATA_PATH.getParent(),
                ".resourcegrabber-chestshop-ids-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    GSON.toJson(idsByServer, DATA_TYPE, writer);
                }
                Files.move(temporary, DATA_PATH, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save learned ChestShop IDs to {}", DATA_PATH, exception);
        }
    }

    public static synchronized Map<String, Map<String, List<String>>> exportServer(
        String serverName
    ) {
        Map<String, Map<String, List<String>>> result = new TreeMap<>();
        idsByServer.getOrDefault(serverName, Map.of()).forEach((scope, items) -> {
            Map<String, List<String>> itemCopy = new TreeMap<>();
            items.forEach((key, ids) -> itemCopy.put(key, List.copyOf(ids)));
            result.put(scope, Map.copyOf(itemCopy));
        });
        return Map.copyOf(result);
    }

    public static synchronized int importServer(
        String serverName, Map<String, Map<String, List<String>>> imported
    ) {
        if (imported == null) {
            return 0;
        }
        Map<String, Map<String, List<String>>> serverIds = idsByServer.computeIfAbsent(
            serverName, ignored -> new TreeMap<>());
        int count = 0;
        for (Map.Entry<String, Map<String, List<String>>> scopeEntry : imported.entrySet()) {
            String scope = scopeEntry.getKey();
            if (scope == null || scope.isBlank() || scope.length() > 256
                || scopeEntry.getValue() == null) {
                continue;
            }
            Map<String, List<String>> worldIds = serverIds.computeIfAbsent(
                scope, ignored -> new TreeMap<>());
            for (Map.Entry<String, List<String>> itemEntry : scopeEntry.getValue().entrySet()) {
                if (itemEntry.getKey() == null || itemEntry.getKey().length() > 256
                    || itemEntry.getValue() == null) {
                    continue;
                }
                List<String> ids = worldIds.computeIfAbsent(
                    itemEntry.getKey(), ignored -> new ArrayList<>());
                for (String id : itemEntry.getValue()) {
                    if (id != null && !id.isBlank() && id.length() <= 1024
                        && !ids.contains(id)) {
                        ids.add(id);
                        count++;
                    }
                }
            }
        }
        if (count > 0) {
            dirty = true;
        }
        return count;
    }

    private static String key(String itemId, int floatIndex, float value) {
        return itemId + '|' + floatIndex + '|'
            + Integer.toHexString(Float.floatToIntBits(value));
    }

    private LearnedChestShopIds() {
    }
}
