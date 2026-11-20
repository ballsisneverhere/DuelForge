package dev.duelforge.util;

import dev.duelforge.DuelForge;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Serialises YAML on the main thread, writes it atomically (optionally async) to avoid lag and corruption. */
public final class Persist {
    private static final Object LOCK = new Object();

    private Persist() {}

    public static void save(DuelForge plugin, File file, YamlConfiguration cfg, boolean async) {
        String data = cfg.saveToString();
        Runnable r = () -> write(plugin, file, data);
        if (async && plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
        else r.run();
    }

    private static void write(DuelForge plugin, File file, String data) {
        synchronized (LOCK) {
            try {
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                Path target = file.toPath();
                Path tmp = target.resolveSibling(file.getName() + ".tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save " + file.getName() + ": " + ex.getMessage());
            }
        }
    }
}
