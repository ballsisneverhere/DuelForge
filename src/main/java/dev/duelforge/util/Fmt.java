package dev.duelforge.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text helpers: colors (including &#RRGGBB), time formatting, enum prettifying. */
public final class Fmt {
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private Fmt() {}

    public static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder r = new StringBuilder("\u00a7x");
            for (char c : m.group(1).toCharArray()) r.append('\u00a7').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(r.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static List<String> color(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) out.add(color(s));
        return out;
    }

    public static String pretty(String enumName) {
        if (enumName == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : enumName.toLowerCase().split("[_ ]")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    public static String time(long seconds) {
        if (seconds < 0) seconds = 0;
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }
}
