package dev.duelforge.arena;

import dev.duelforge.match.Match;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** A cuboid PvP arena with spawn points. Only one match can use an arena at a time. */
public final class Arena {

    /** World-independent position. */
    public static final class Pos {
        public final double x, y, z;
        public final float yaw, pitch;

        public Pos(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public static Pos of(Location l) {
            return new Pos(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        public Location at(World w) {
            return new Location(w, x, y, z, yaw, pitch);
        }

        public static Pos parse(String s) {
            String[] a = s.split(",");
            return new Pos(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]),
                    Float.parseFloat(a[3]), Float.parseFloat(a[4]));
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z + "," + yaw + "," + pitch;
        }
    }

    public final String name;
    public String displayName;
    public Material icon = Material.GRASS_BLOCK;
    public String worldName = "world";
    public boolean boundsSet;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int[] p1, p2;
    public final List<Pos> spawns = new ArrayList<>();
    public Pos spectator;
    public boolean enabled = true;
    public boolean allowBuilding = true;
    /** True for per-match maps that are built on demand and wiped afterwards. */
    public boolean temporary;
    /** The match currently reserving this arena (including block restoration). */
    public Match current;

    public Arena(String name) {
        this.name = name;
        this.displayName = name;
    }

    public void recalc() {
        if (p1 == null || p2 == null) return;
        minX = Math.min(p1[0], p2[0]);
        minY = Math.min(p1[1], p2[1]);
        minZ = Math.min(p1[2], p2[2]);
        maxX = Math.max(p1[0], p2[0]);
        maxY = Math.max(p1[1], p2[1]);
        maxZ = Math.max(p1[2], p2[2]);
        boundsSet = true;
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public boolean ready() {
        return enabled && boundsSet && spawns.size() >= 2 && world() != null;
    }

    public boolean free() {
        return ready() && current == null;
    }

    public boolean contains(Location l) {
        return l != null && l.getWorld() != null && l.getWorld().getName().equals(worldName)
                && l.getX() >= minX && l.getX() < maxX + 1
                && l.getY() >= minY && l.getY() < maxY + 1
                && l.getZ() >= minZ && l.getZ() < maxZ + 1;
    }

    public boolean containsBlock(Block b) {
        return b.getWorld().getName().equals(worldName)
                && b.getX() >= minX && b.getX() <= maxX
                && b.getY() >= minY && b.getY() <= maxY
                && b.getZ() >= minZ && b.getZ() <= maxZ;
    }

    public Location spawn(int index) {
        return spawns.get(Math.floorMod(index, spawns.size())).at(world());
    }

    public Location spectatorSpawn() {
        if (spectator != null) return spectator.at(world());
        Pos s = spawns.get(0);
        return new Location(world(), s.x, s.y + 6, s.z, s.yaw, s.pitch);
    }

    public BoundingBox box() {
        return new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }
}
