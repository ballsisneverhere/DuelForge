package dev.duelforge.arena;

import dev.duelforge.DuelForge;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds ready-made PvP maps (and a walkable hub with bridges) from code.
 * Placement is spread over several ticks so building never lags the server.
 * All floors sit at y0; every arena is fully enclosed by its registered bounds.
 */
public final class ArenaBuilder {

    public enum Theme {
        COLOSSEUM("&6&lColosseum", Material.STONE_BRICKS),
        SKY("&b&lSky Islands", Material.GRASS_BLOCK),
        CASTLE("&7&lCastle", Material.CRACKED_STONE_BRICKS),
        WASTELAND("&e&lWasteland", Material.SANDSTONE);

        public final String display;
        public final Material icon;

        Theme(String display, Material icon) {
            this.display = display;
            this.icon = icon;
        }

        public static Theme parse(String s) {
            for (Theme t : values()) if (t.name().equalsIgnoreCase(s)) return t;
            return null;
        }
    }

    public record Op(int x, int y, int z, Material m) {}

    /** Blocks to place plus the arena data that goes with them. */
    public static final class Plan {
        public final List<Op> ops = new ArrayList<>();
        public int[] min, max;
        public final List<Arena.Pos> spawns = new ArrayList<>();
        public Arena.Pos spectator;
        public String display = "";
        public Material icon = Material.STONE;
    }

    private static final class Canvas {
        final List<Op> ops;

        Canvas(List<Op> ops) {
            this.ops = ops;
        }

        void set(int x, int y, int z, Material m) {
            ops.add(new Op(x, y, z, m));
        }

        void box(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, m);
        }

        void ring(int cx, int cz, double rOut, double rIn, int y1, int y2, Material m) {
            int r = (int) Math.ceil(rOut);
            double o2 = rOut * rOut, i2 = rIn * rIn;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    double d = dx * dx + dz * dz;
                    if (d <= o2 && d >= i2) for (int y = y1; y <= y2; y++) set(cx + dx, y, cz + dz, m);
                }
        }

        void cyl(int cx, int cz, double r, int y1, int y2, Material m) {
            ring(cx, cz, r, 0, y1, y2, m);
        }
    }

    private ArenaBuilder() {}

    // =====================================================================
    // public API
    // =====================================================================

    public static Plan plan(Theme t, int cx, int y0, int cz, boolean clear) {
        Plan p = switch (t) {
            case COLOSSEUM -> colosseum(cx, y0, cz, clear);
            case SKY -> sky(cx, y0, cz, clear);
            case CASTLE -> castle(cx, y0, cz, clear);
            case WASTELAND -> wasteland(cx, y0, cz, clear);
        };
        p.display = t.display;
        p.icon = t.icon;
        return p;
    }

    /** Registers the plan as an arena (replacing any arena with the same name). */
    public static Arena register(ArenaManager am, String name, World w, Plan p) {
        am.delete(name);
        Arena a = am.create(name, w);
        a.displayName = p.display;
        a.icon = p.icon;
        a.p1 = p.min;
        a.p2 = p.max;
        a.recalc();
        a.spawns.addAll(p.spawns);
        a.spectator = p.spectator;
        a.allowBuilding = true;
        return a;
    }

    /** Places the blocks a few thousand per tick, then runs {@code done}. */
    public static void apply(DuelForge plugin, World w, List<Op> ops, Runnable done) {
        final int perTick = 6000;
        final int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int n = 0;
                while (i < ops.size() && n < perTick) {
                    Op o = ops.get(i++);
                    if (o.y() < minY || o.y() >= maxY) continue;
                    w.getBlockAt(o.x(), o.y(), o.z()).setType(o.m(), false);
                    n++;
                }
                if (i >= ops.size()) {
                    cancel();
                    if (done != null) done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static void cardinalSpawns(Plan p, int cx, int y, int cz, int d) {
        p.spawns.add(new Arena.Pos(cx + 0.5 + d, y, cz + 0.5, 90f, 0f));
        p.spawns.add(new Arena.Pos(cx + 0.5 - d, y, cz + 0.5, -90f, 0f));
        p.spawns.add(new Arena.Pos(cx + 0.5, y, cz + 0.5 + d, 180f, 0f));
        p.spawns.add(new Arena.Pos(cx + 0.5, y, cz + 0.5 - d, 0f, 0f));
    }

    private static void bounds(Plan p, int cx, int cz, int half, int minY, int maxY) {
        p.min = new int[]{cx - half, minY, cz - half};
        p.max = new int[]{cx + half, maxY, cz + half};
    }

    // =====================================================================
    // themes
    // =====================================================================

    private static Plan colosseum(int cx, int y0, int cz, boolean clear) {
        Plan p = new Plan();
        Canvas c = new Canvas(p.ops);
        final int R = 22, B = R + 7;
        if (clear) c.box(cx - B, y0 + 1, cz - B, cx + B, y0 + 22, cz + B, Material.AIR);
        c.cyl(cx, cz, R + 7.5, y0 - 3, y0 - 1, Material.STONE_BRICKS);
        c.cyl(cx, cz, R, y0, y0, Material.STONE_BRICKS);
        c.cyl(cx, cz, R - 4, y0, y0, Material.SMOOTH_STONE);
        c.cyl(cx, cz, 6, y0, y0, Material.POLISHED_ANDESITE);
        c.cyl(cx, cz, 2, y0, y0, Material.CHISELED_STONE_BRICKS);
        // wall + two tiers of stands
        c.ring(cx, cz, R + 1.5, R - 0.5, y0 + 1, y0 + 3, Material.STONE_BRICKS);
        c.ring(cx, cz, R + 4.5, R + 1.5, y0, y0 + 3, Material.QUARTZ_BLOCK);
        c.ring(cx, cz, R + 7.5, R + 4.5, y0, y0 + 5, Material.QUARTZ_BLOCK);
        // lantern pillars between the gates
        for (int k = 0; k < 8; k++) {
            double ang = Math.toRadians(22.5 + 45 * k);
            int x = cx + (int) Math.round((R + 0.5) * Math.cos(ang));
            int z = cz + (int) Math.round((R + 0.5) * Math.sin(ang));
            c.box(x, y0 + 1, z, x, y0 + 8, z, Material.POLISHED_ANDESITE);
            c.set(x, y0 + 9, z, Material.SEA_LANTERN);
        }
        // four gates (carved through wall and stands)
        c.box(cx + R - 1, y0 + 1, cz - 2, cx + R + 8, y0 + 6, cz + 2, Material.AIR);
        c.box(cx - R - 8, y0 + 1, cz - 2, cx - R + 1, y0 + 6, cz + 2, Material.AIR);
        c.box(cx - 2, y0 + 1, cz + R - 1, cx + 2, y0 + 6, cz + R + 8, Material.AIR);
        c.box(cx - 2, y0 + 1, cz - R - 8, cx + 2, y0 + 6, cz - R + 1, Material.AIR);
        // cover
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                c.box(cx + sx * 9, y0 + 1, cz + sz * 9, cx + sx * 9 + 1, y0 + 4, cz + sz * 9 + 1, Material.POLISHED_ANDESITE);
        bounds(p, cx, cz, B, y0 - 6, y0 + 30);
        cardinalSpawns(p, cx, y0 + 1, cz, R - 5);
        p.spectator = new Arena.Pos(cx + 0.5, y0 + 12, cz + 0.5, 0f, 45f);
        return p;
    }

    private static Plan sky(int cx, int y0, int cz, boolean clear) {
        Plan p = new Plan();
        Canvas c = new Canvas(p.ops);
        final int R = 16, S = 26, B = S + 6;
        if (clear) c.box(cx - B, y0 - 12, cz - B, cx + B, y0 + 30, cz + B, Material.AIR);
        for (int k = 0; k <= 6; k++) {
            double r = Math.max(2, R - k * 2);
            c.cyl(cx, cz, r, y0 - k, y0 - k, k == 0 ? Material.GRASS_BLOCK : (k <= 2 ? Material.DIRT : Material.STONE));
        }
        int[][] sats = {{S, 0}, {-S, 0}, {0, S}, {0, -S}};
        for (int[] s : sats) {
            int sx = cx + s[0], sz = cz + s[1];
            for (int k = 0; k <= 3; k++) {
                c.cyl(sx, sz, 5 - k, y0 - k, y0 - k, k == 0 ? Material.GRASS_BLOCK : (k == 1 ? Material.DIRT : Material.STONE));
            }
        }
        // plank bridges between the islands
        c.box(cx + R - 1, y0, cz - 1, cx + S - 4, y0, cz + 1, Material.SPRUCE_PLANKS);
        c.box(cx - S + 4, y0, cz - 1, cx - R + 1, y0, cz + 1, Material.SPRUCE_PLANKS);
        c.box(cx - 1, y0, cz + R - 1, cx + 1, y0, cz + S - 4, Material.SPRUCE_PLANKS);
        c.box(cx - 1, y0, cz - S + 4, cx + 1, y0, cz - R + 1, Material.SPRUCE_PLANKS);
        // cover pillars
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                c.box(cx + sx * 8, y0 + 1, cz + sz * 8, cx + sx * 8, y0 + 5, cz + sz * 8, Material.STONE_BRICKS);
                c.set(cx + sx * 8, y0 + 6, cz + sz * 8, Material.SEA_LANTERN);
            }
        bounds(p, cx, cz, B, y0 - 12, y0 + 30);
        cardinalSpawns(p, cx, y0 + 1, cz, R - 4);
        p.spectator = new Arena.Pos(cx + 0.5, y0 + 14, cz + 0.5, 0f, 45f);
        return p;
    }

    private static Plan castle(int cx, int y0, int cz, boolean clear) {
        Plan p = new Plan();
        Canvas c = new Canvas(p.ops);
        final int H = 20, B = H + 3;
        if (clear) c.box(cx - B, y0 + 1, cz - B, cx + B, y0 + 22, cz + B, Material.AIR);
        c.box(cx - B, y0 - 3, cz - B, cx + B, y0 - 1, cz + B, Material.STONE_BRICKS);
        c.box(cx - B, y0, cz - B, cx + B, y0, cz + B, Material.STONE_BRICKS);
        for (int x = cx - H; x <= cx + H; x++)
            for (int z = cz - H; z <= cz + H; z++) {
                if (Math.floorMod(x + z, 5) == 0) c.set(x, y0, z, Material.CRACKED_STONE_BRICKS);
                else if (Math.floorMod(x * 7 + z * 13, 11) == 0) c.set(x, y0, z, Material.MOSSY_STONE_BRICKS);
            }
        // four wall sections (thickness 3)
        c.box(cx - B, y0 + 1, cz - B, cx + B, y0 + 10, cz - H - 1, Material.STONE_BRICKS);
        c.box(cx - B, y0 + 1, cz + H + 1, cx + B, y0 + 10, cz + B, Material.STONE_BRICKS);
        c.box(cx - B, y0 + 1, cz - H, cx - H - 1, y0 + 10, cz + H, Material.STONE_BRICKS);
        c.box(cx + H + 1, y0 + 1, cz - H, cx + B, y0 + 10, cz + H, Material.STONE_BRICKS);
        // crenellations
        for (int x = cx - B; x <= cx + B; x++)
            for (int z = cz - B; z <= cz + B; z++) {
                boolean inWall = Math.abs(x - cx) > H || Math.abs(z - cz) > H;
                if (inWall && Math.floorMod(x + z, 2) == 0) c.set(x, y0 + 11, z, Material.STONE_BRICKS);
            }
        // gates
        c.box(cx + H, y0 + 1, cz - 2, cx + B, y0 + 6, cz + 2, Material.AIR);
        c.box(cx - B, y0 + 1, cz - 2, cx - H, y0 + 6, cz + 2, Material.AIR);
        c.box(cx - 2, y0 + 1, cz + H, cx + 2, y0 + 6, cz + B, Material.AIR);
        c.box(cx - 2, y0 + 1, cz - B, cx + 2, y0 + 6, cz - H, Material.AIR);
        // corner towers
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                int x1 = sx > 0 ? cx + B - 6 : cx - B, x2 = sx > 0 ? cx + B : cx - B + 6;
                int z1 = sz > 0 ? cz + B - 6 : cz - B, z2 = sz > 0 ? cz + B : cz - B + 6;
                c.box(x1, y0 + 1, z1, x2, y0 + 16, z2, Material.STONE_BRICKS);
                for (int x = x1; x <= x2; x++)
                    for (int z = z1; z <= z2; z++) {
                        if (Math.floorMod(x + z, 2) == 0) c.set(x, y0 + 17, z, Material.STONE_BRICKS);
                    }
                c.set((x1 + x2) / 2, y0 + 17, (z1 + z2) / 2, Material.LANTERN);
            }
        // altar + cover walls
        c.box(cx - 2, y0 + 1, cz - 2, cx + 2, y0 + 1, cz + 2, Material.POLISHED_BLACKSTONE_BRICKS);
        c.box(cx - 1, y0 + 2, cz - 1, cx + 1, y0 + 2, cz + 1, Material.POLISHED_BLACKSTONE_BRICKS);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                c.box(cx + sx * 10 - 2, y0 + 1, cz + sz * 10, cx + sx * 10 + 2, y0 + 2, cz + sz * 10, Material.STONE_BRICKS);
        bounds(p, cx, cz, B, y0 - 6, y0 + 30);
        cardinalSpawns(p, cx, y0 + 1, cz, H - 4);
        p.spectator = new Arena.Pos(cx + 0.5, y0 + 14, cz + 0.5, 0f, 45f);
        return p;
    }

    private static Plan wasteland(int cx, int y0, int cz, boolean clear) {
        Plan p = new Plan();
        Canvas c = new Canvas(p.ops);
        final int H = 26, B = H + 1;
        if (clear) c.box(cx - B, y0 + 1, cz - B, cx + B, y0 + 22, cz + B, Material.AIR);
        c.box(cx - B, y0 - 3, cz - B, cx + B, y0 - 1, cz + B, Material.SANDSTONE);
        Random rnd = new Random(cx * 31L + cz * 17L + 7L);
        for (int x = cx - B; x <= cx + B; x++)
            for (int z = cz - B; z <= cz + B; z++) {
                int r = rnd.nextInt(100);
                c.set(x, y0, z, r < 12 ? Material.TERRACOTTA : (r < 22 ? Material.CUT_SANDSTONE : Material.SMOOTH_SANDSTONE));
            }
        // low rim with four gaps
        for (int x = cx - B; x <= cx + B; x++)
            for (int z = cz - B; z <= cz + B; z++) {
                if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) == B) c.box(x, y0 + 1, z, x, y0 + 2, z, Material.CUT_SANDSTONE);
            }
        c.box(cx + B, y0 + 1, cz - 2, cx + B, y0 + 2, cz + 2, Material.AIR);
        c.box(cx - B, y0 + 1, cz - 2, cx - B, y0 + 2, cz + 2, Material.AIR);
        c.box(cx - 2, y0 + 1, cz + B, cx + 2, y0 + 2, cz + B, Material.AIR);
        c.box(cx - 2, y0 + 1, cz - B, cx + 2, y0 + 2, cz - B, Material.AIR);
        // central ruined temple
        c.cyl(cx, cz, 3, y0, y0, Material.SMOOTH_RED_SANDSTONE);
        for (int k = 0; k < 8; k++) {
            double ang = Math.toRadians(22.5 + 45 * k);
            int x = cx + (int) Math.round(6 * Math.cos(ang));
            int z = cz + (int) Math.round(6 * Math.sin(ang));
            c.box(x, y0 + 1, z, x, y0 + 3 + rnd.nextInt(4), z, Material.SANDSTONE);
        }
        // scattered ruins (kept clear of the spawn points)
        int d = H - 4;
        int[][] sp = {{d, 0}, {-d, 0}, {0, d}, {0, -d}};
        int range = H - 5;
        for (int i = 0; i < 16; i++) {
            int x = cx + rnd.nextInt(2 * range + 1) - range;
            int z = cz + rnd.nextInt(2 * range + 1) - range;
            boolean near = Math.abs(x - cx) < 9 && Math.abs(z - cz) < 9;
            for (int[] s : sp) if (Math.abs(x - cx - s[0]) < 6 && Math.abs(z - cz - s[1]) < 6) near = true;
            int kind = rnd.nextInt(3);
            Material m = rnd.nextBoolean() ? Material.SANDSTONE : Material.CUT_SANDSTONE;
            if (near) continue;
            if (kind == 0) {
                int h = 3 + rnd.nextInt(6);
                c.box(x, y0 + 1, z, x, y0 + h, z, m);
                c.set(x, y0 + h + 1, z, Material.CHISELED_SANDSTONE);
            } else if (kind == 1) {
                int len = 3 + rnd.nextInt(4), h = 2 + rnd.nextInt(3);
                if (rnd.nextBoolean()) c.box(x, y0 + 1, z, x + len, y0 + h, z, m);
                else c.box(x, y0 + 1, z, x, y0 + h, z + len, m);
            } else {
                int len = 3 + rnd.nextInt(3), h = 2 + rnd.nextInt(3);
                c.box(x, y0 + 1, z, x + len, y0 + h, z, m);
                c.box(x, y0 + 1, z, x, y0 + h, z + len, m);
            }
        }
        bounds(p, cx, cz, B, y0 - 6, y0 + 30);
        cardinalSpawns(p, cx, y0 + 1, cz, H - 4);
        p.spectator = new Arena.Pos(cx + 0.5, y0 + 14, cz + 0.5, 0f, 45f);
        return p;
    }

    // =====================================================================
    // hub + bridges used by /duel admin autosetup
    // =====================================================================

    /** Arena centres relative to the hub, matching the bridges below. */
    public static final int ARENA_DISTANCE = 70;

    public static Plan hub(int cx, int y0, int cz, boolean clear) {
        Plan p = new Plan();
        Canvas c = new Canvas(p.ops);
        final int R = 16;
        if (clear) c.box(cx - R - 1, y0 + 1, cz - R - 1, cx + R + 1, y0 + 16, cz + R + 1, Material.AIR);
        c.cyl(cx, cz, R, y0 - 3, y0 - 1, Material.STONE_BRICKS);
        c.cyl(cx, cz, R, y0, y0, Material.QUARTZ_BLOCK);
        c.ring(cx, cz, R + 0.5, R - 2.0, y0, y0, Material.SMOOTH_QUARTZ);
        c.cyl(cx, cz, 5, y0, y0, Material.POLISHED_ANDESITE);
        c.cyl(cx, cz, 2, y0, y0, Material.SEA_LANTERN);
        for (int k = 0; k < 8; k++) {
            double ang = Math.toRadians(22.5 + 45 * k);
            int x = cx + (int) Math.round((R - 2) * Math.cos(ang));
            int z = cz + (int) Math.round((R - 2) * Math.sin(ang));
            c.box(x, y0 + 1, z, x, y0 + 3, z, Material.STONE_BRICK_WALL);
            c.set(x, y0 + 4, z, Material.SEA_LANTERN);
        }
        c.ring(cx, cz, R + 0.5, R - 0.5, y0 + 1, y0 + 1, Material.STONE_BRICKS);
        c.box(cx + R - 1, y0 + 1, cz - 2, cx + R + 1, y0 + 1, cz + 2, Material.AIR);
        c.box(cx - R - 1, y0 + 1, cz - 2, cx - R + 1, y0 + 1, cz + 2, Material.AIR);
        c.box(cx - 2, y0 + 1, cz + R - 1, cx + 2, y0 + 1, cz + R + 1, Material.AIR);
        c.box(cx - 2, y0 + 1, cz - R - 1, cx + 2, y0 + 1, cz - R + 1, Material.AIR);
        p.spectator = new Arena.Pos(cx + 0.5, y0 + 1, cz + 0.5, 0f, 0f); // used as the lobby spawn
        return p;
    }

    /** Walkable bridges (with low rails) from the hub at (0,0) to the four arenas. */
    public static List<Op> bridges(int y0) {
        List<Op> ops = new ArrayList<>();
        Canvas c = new Canvas(ops);
        bridgeX(c, 17, 41, y0);
        bridgeX(c, -47, -17, y0);
        bridgeZ(c, 17, 43, y0);
        bridgeZ(c, -39, -17, y0);
        return ops;
    }

    private static void bridgeX(Canvas c, int x1, int x2, int y0) {
        c.box(x1, y0, -3, x2, y0, 3, Material.POLISHED_ANDESITE);
        c.box(x1, y0 + 1, -3, x2, y0 + 1, -3, Material.STONE_BRICKS);
        c.box(x1, y0 + 1, 3, x2, y0 + 1, 3, Material.STONE_BRICKS);
    }

    private static void bridgeZ(Canvas c, int z1, int z2, int y0) {
        c.box(-3, y0, z1, 3, y0, z2, Material.POLISHED_ANDESITE);
        c.box(-3, y0 + 1, z1, -3, y0 + 1, z2, Material.STONE_BRICKS);
        c.box(3, y0 + 1, z1, 3, y0 + 1, z2, Material.STONE_BRICKS);
    }
}
