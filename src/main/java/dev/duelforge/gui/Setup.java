package dev.duelforge.gui;

import dev.duelforge.arena.Arena;
import dev.duelforge.kit.Kit;

import java.util.UUID;

/** A player's in-progress duel / FFA configuration while they browse menus. */
public final class Setup {
    public UUID target;     // null for FFA
    public boolean ffa;
    public Kit kit;
    public Arena arena;     // null = random
    public boolean ranked;
}
