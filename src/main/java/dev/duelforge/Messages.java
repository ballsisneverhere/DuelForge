package dev.duelforge;

import dev.duelforge.util.Fmt;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Loads messages.yml (falling back to the bundled defaults) and handles placeholders. */
public final class Messages {
    private final DuelForge plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private String prefix = "";

    public Messages(DuelForge plugin) {
        this.plugin = plugin;
    }

    public void load() {
        cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        var in = plugin.getResource("messages.yml");
        if (in != null) cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        prefix = cfg.getString("prefix", "");
    }

    public String raw(String key) {
        String s = cfg.getString(key);
        return s == null ? "&c[missing: " + key + "]" : s;
    }

    public static String apply(String s, Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        return s;
    }

    public static String apply(String s, Map<String, String> map) {
        for (Map.Entry<String, String> e : map.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s;
    }

    /** Colored message without prefix. */
    public String get(String key, Object... kv) {
        return Fmt.color(apply(raw(key), kv));
    }

    public String prefixed(String key, Object... kv) {
        return Fmt.color(prefix + apply(raw(key), kv));
    }

    public List<String> list(String key, Object... kv) {
        List<String> out = new ArrayList<>();
        for (String s : cfg.getStringList(key)) out.add(Fmt.color(apply(s, kv)));
        return out;
    }

    public List<String> rawList(String key) {
        return cfg.getStringList(key);
    }

    public void send(CommandSender to, String key, Object... kv) {
        if (to != null) to.sendMessage(prefixed(key, kv));
    }

    public void actionBar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }

    /** Sends a chat line followed by clickable buttons: {label, command, hover}. */
    public void sendButtons(Player p, String line, String[]... buttons) {
        TextComponent root = new TextComponent();
        for (BaseComponent b : TextComponent.fromLegacyText(line)) root.addExtra(b);
        for (String[] btn : buttons) {
            TextComponent c = new TextComponent();
            for (BaseComponent b : TextComponent.fromLegacyText(btn[0])) c.addExtra(b);
            c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, btn[1]));
            c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(btn[2]).create()));
            root.addExtra(new TextComponent(" "));
            root.addExtra(c);
        }
        p.spigot().sendMessage(root);
    }
}
