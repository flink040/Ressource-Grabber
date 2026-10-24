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
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

final class FavoriteItems {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Set<String>>() { }.getType();
    private static final Path DATA_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("resourcegrabber-favorites.json");
    private static final Set<String> favorites = new HashSet<>();
    private static boolean dirty;

    static synchronized void load() {
        favorites.clear();
        if (!Files.isRegularFile(DATA_PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(DATA_PATH)) {
            Set<String> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded != null) {
                loaded.stream().filter(java.util.Objects::nonNull).forEach(favorites::add);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load Resource Grabber favorites", exception);
        }
    }

    static synchronized boolean contains(CustomItemEntry entry) {
        return favorites.contains(entry.stableKey());
    }

    static synchronized boolean toggle(CustomItemEntry entry) {
        String key = entry.stableKey();
        boolean active;
        if (favorites.remove(key)) {
            active = false;
        } else {
            favorites.add(key);
            active = true;
        }
        dirty = true;
        saveIfDirty();
        return active;
    }

    static synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Path temporary = Files.createTempFile(DATA_PATH.getParent(),
                ".resourcegrabber-favorites-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    GSON.toJson(favorites, DATA_TYPE, writer);
                }
                Files.move(temporary, DATA_PATH, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not save Resource Grabber favorites", exception);
        }
    }

    private FavoriteItems() {
    }
}
