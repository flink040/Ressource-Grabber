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
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
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
        CustomModelDataComponent modelData = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (modelData == null || modelData.floats().isEmpty()) {
            return;
        }
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return "unknown";
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        ScoreboardObjective sidebar = client.world.getScoreboard()
            .getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
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

    private static String key(String itemId, int floatIndex, float value) {
        return itemId + '|' + floatIndex + '|'
            + Integer.toHexString(Float.floatToIntBits(value));
    }

    private LearnedChestShopIds() {
    }
}
