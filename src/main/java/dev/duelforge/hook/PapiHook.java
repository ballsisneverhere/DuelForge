package dev.duelforge.hook;

import dev.duelforge.DuelForge;

/** Isolated so PlaceholderAPI classes are only loaded when the plugin is actually installed. */
public final class PapiHook {
    private PapiHook() {}

    public static void register(DuelForge plugin) {
        new DuelForgeExpansion(plugin).register();
    }
}
