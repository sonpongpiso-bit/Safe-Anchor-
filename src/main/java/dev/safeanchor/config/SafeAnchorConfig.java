package dev.safeanchor.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SafeAnchorConfig {
    public static final int MIN_TICKS = 1;
    public static final int MAX_TICKS = 5;
    public static final int MIN_SCROLL = 0;
    public static final int MAX_SCROLL = 3;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("safeanchor.json");
    private static SafeAnchorConfig instance = new SafeAnchorConfig();

    public int activationKey = GLFW.GLFW_KEY_R;
    public int menuKey = GLFW.GLFW_KEY_INSERT;
    public int detonationSlot = 1;
    public int tickSpeed = 2;
    public int hotbarScrollDelay = 1;

    public static SafeAnchorConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                SafeAnchorConfig loaded = GSON.fromJson(reader, SafeAnchorConfig.class);
                if (loaded != null) instance = loaded;
            } catch (Exception e) {
                System.err.println("[SafeAnchor] Failed to read config, using defaults: " + e);
            }
        }
        save();
    }

    public static void save() {
        instance.sanitize();
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            System.err.println("[SafeAnchor] Failed to save config: " + e);
        }
    }

    private void sanitize() {
        tickSpeed = Math.max(MIN_TICKS, Math.min(MAX_TICKS, tickSpeed));
        detonationSlot = Math.max(1, Math.min(9, detonationSlot));
        hotbarScrollDelay = Math.max(MIN_SCROLL, Math.min(MAX_SCROLL, hotbarScrollDelay));
        if (activationKey < -1) activationKey = -1;
        if (menuKey < 0) menuKey = GLFW.GLFW_KEY_INSERT;
    }
}
