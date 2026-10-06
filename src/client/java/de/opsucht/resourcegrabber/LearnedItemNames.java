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

    private static final Map<String, Map<String, String>> namesByServer = new HashMap<>();
    private static boolean dirty;

    public static synchronized void load() {
        namesByServer.clear();
        if (!Files.isRegularFile(DATA_PATH)) {
            return;
        }
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

    public static synchronized void observe(String serverName, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        CustomModelDataComponent modelData = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (modelData == null || modelData.floats().isEmpty()) {
            return;
        }

        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            name = stack.get(DataComponentTypes.ITEM_NAME);
        }
        if (name == null || name.getString().isBlank()) {
            return;
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        String displayName = name.getString().strip();
        Map<String, String> serverNames = namesByServer.computeIfAbsent(
            serverName, ignored -> new HashMap<>());
        List<Float> floats = modelData.floats();
        for (int index = 0; index < floats.size(); index++) {
            float value = floats.get(index);
            if (!CustomItemCatalog.contains(itemId, index, value)) {
                continue;
            }
            String previous = serverNames.put(key(itemId, index, value), displayName);
            if (!displayName.equals(previous)) {
                dirty = true;
                LOGGER.info("Learned custom-item name '{}' for {} CMD[{}]={}",
                    displayName, itemId, index, value);
            }
        }
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
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save learned custom-item names to {}", DATA_PATH, exception);
        }
    }

    private static String key(String itemId, int floatIndex, float value) {
        return itemId + '|' + floatIndex + '|' + Integer.toHexString(Float.floatToIntBits(value));
    }

    private static int nameCount() {
        return namesByServer.values().stream().mapToInt(Map::size).sum();
    }

    private LearnedItemNames() {
    }
}
