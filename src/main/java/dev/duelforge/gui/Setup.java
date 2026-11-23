package dev.duelforge.gui;

import dev.duelforge.arena.Arena;
import dev.duelforge.kit.Kit;

import java.util.UUID;

/** A player's in-progress duel / FFA / queue configuration while they browse menus. */
public final class Setup {
    public enum Type { DUEL, FFA, QUEUE }

    public Type type = Type.DUEL;
    public UUID target;     // duel opponent
    public Kit kit;
    public Arena arena;     // null = a fresh map is built for the match
    public boolean ranked;
    public int teamSize = 1;
    public int bestOf = 3;
}
