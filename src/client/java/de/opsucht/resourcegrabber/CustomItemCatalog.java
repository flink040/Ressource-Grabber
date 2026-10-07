package de.opsucht.resourcegrabber;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

public final class CustomItemCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile List<CustomItemEntry> items = List.of();
    private static volatile Set<String> itemKeys = Set.of();
    private static volatile String sourceServer = "server";

    public static List<CustomItemEntry> snapshot() {
        return items;
    }

    public static String sourceServer() {
        return sourceServer;
    }

    public static boolean contains(String itemId, int floatIndex, float threshold) {
        return itemKeys.contains(itemKey(itemId, floatIndex, threshold));
    }

    public static void replace(List<CustomItemEntry> discovered, String serverName) {
        Map<String, CustomItemEntry> unique = new LinkedHashMap<>();
        for (CustomItemEntry entry : discovered) {
            String key = entry.itemId() + '|' + entry.floatIndex() + '|'
                + entry.threshold() + '|' + entry.modelId();
            unique.putIfAbsent(key, entry);
        }
        List<CustomItemEntry> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(CustomItemEntry::itemId)
            .thenComparingInt(CustomItemEntry::floatIndex)
            .thenComparingDouble(CustomItemEntry::threshold)
            .thenComparing(CustomItemEntry::modelId));
        items = List.copyOf(sorted);
        Set<String> discoveredKeys = new HashSet<>();
        for (CustomItemEntry entry : items) {
            discoveredKeys.add(itemKey(entry.itemId(), entry.floatIndex(), entry.threshold()));
        }
        itemKeys = Set.copyOf(discoveredKeys);
        sourceServer = serverName;
        LOGGER.info("Found {} custom item models in resource packs from {}",
            items.size(), sourceServer);
    }

    private static String itemKey(String itemId, int floatIndex, float threshold) {
        return itemId + '|' + floatIndex + '|'
            + Integer.toHexString(Float.floatToIntBits(threshold));
    }

    public static List<CustomItemEntry> readPack(Path packPath) throws IOException {
        List<CustomItemEntry> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".json")) {
                    continue;
                }
                Optional<ItemDefinition> definition = getItemDefinition(entry.getName());
                if (definition.isEmpty()) {
                    continue;
                }
                try (Reader reader = new InputStreamReader(
                    zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    JsonElement json = JsonParser.parseReader(reader);
                    if (definition.get().legacy()) {
                        readLegacyOverrides(json, definition.get().itemId(), result);
                    } else {
                        readModernModels(json, definition.get().itemId(), result);
                    }
                } catch (RuntimeException exception) {
                    LOGGER.debug("Could not parse custom item definition {} in {}",
                        entry.getName(), packPath, exception);
                }
            }
        }
        return result;
    }

    private static Optional<ItemDefinition> getItemDefinition(String name) {
        String[] parts = name.split("/");
        if (parts.length < 4 || !"assets".equals(parts[0])) {
            return Optional.empty();
        }
        String namespace = parts[1];
        if ("items".equals(parts[2])) {
            String path = join(parts, 3, parts.length);
            String itemId = namespace + ':' + removeJsonSuffix(path);
            return isKnownItem(itemId)
                ? Optional.of(new ItemDefinition(itemId, false))
                : Optional.empty();
        }
        if (parts.length == 5 && "models".equals(parts[2]) && "item".equals(parts[3])) {
            String path = join(parts, 4, parts.length);
            String itemId = namespace + ':' + removeJsonSuffix(path);
            return isKnownItem(itemId)
                ? Optional.of(new ItemDefinition(itemId, true))
                : Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isKnownItem(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        return identifier != null && BuiltInRegistries.ITEM.containsKey(identifier);
    }

    private static void readModernModels(
        JsonElement element, String itemId, List<CustomItemEntry> output
    ) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                readModernModels(child, itemId, output);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        String property = stringValue(object.get("property"));
        if (property != null && property.endsWith("custom_model_data")
            && object.has("entries") && object.get("entries").isJsonArray()) {
            int index = object.has("index") ? Math.max(0, object.get("index").getAsInt()) : 0;
            for (JsonElement entryElement : object.getAsJsonArray("entries")) {
                if (!entryElement.isJsonObject()) {
                    continue;
                }
                JsonObject modelEntry = entryElement.getAsJsonObject();
                if (!modelEntry.has("threshold")) {
                    continue;
                }
                String modelId = findModelId(modelEntry.get("model"));
                if (modelId != null) {
                    output.add(new CustomItemEntry(itemId, index,
                        modelEntry.get("threshold").getAsFloat(), modelId));
                }
            }
        }

        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            readModernModels(child.getValue(), itemId, output);
        }
    }

    private static void readLegacyOverrides(
        JsonElement element, String itemId, List<CustomItemEntry> output
    ) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonElement overridesElement = element.getAsJsonObject().get("overrides");
        if (overridesElement == null || !overridesElement.isJsonArray()) {
            return;
        }
        for (JsonElement overrideElement : overridesElement.getAsJsonArray()) {
            if (!overrideElement.isJsonObject()) {
                continue;
            }
            JsonObject override = overrideElement.getAsJsonObject();
            JsonObject predicate = override.has("predicate") && override.get("predicate").isJsonObject()
                ? override.getAsJsonObject("predicate") : null;
            if (predicate == null || !predicate.has("custom_model_data")) {
                continue;
            }
            String modelId = stringValue(override.get("model"));
            if (modelId != null) {
                output.add(new CustomItemEntry(itemId, 0,
                    predicate.get("custom_model_data").getAsFloat(), modelId));
            }
        }
    }

    private static String findModelId(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String found = findModelId(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("model") && object.get("model").isJsonPrimitive()) {
            return object.get("model").getAsString();
        }
        for (Map.Entry<String, JsonElement> child : object.entrySet()) {
            String found = findModelId(child.getValue());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String stringValue(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String join(String[] parts, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(parts[index]);
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static String removeJsonSuffix(String path) {
        return path.substring(0, path.length() - ".json".length());
    }

    private record ItemDefinition(String itemId, boolean legacy) {
    }

    private CustomItemCatalog() {
    }
}
