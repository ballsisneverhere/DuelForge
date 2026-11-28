# DuelForge

A full-featured **kit PvP / duels plugin** for **Paper and Spigot** (1.20.x, Java 17).
Challenge friends, run free-for-alls, play ranked, spectate, edit your kit layout, and enjoy a polished GUI.

## Kits (35) - see KITS.md for every loadout

Kits wear **diamond armor** (Protection II-III + Unbreaking II on most); **OP, Juggernaut and Balls use Netherite**; Sumo has none. **Everything has durability**.
Classic, UHC (3 water + 3 lava buckets), OP, Archer, Tank, Ninja, Axe, **Balls**, **Vanilla** (no abilities), NoDebuff, Debuff,
Pot PvP, Gapple, Soup, Combo, Sumo, Bow Fight, Crossbow, Knight, Assassin, Mage, Hunter, Windrunner, Medic, Juggernaut,
Bunny, Hardcore, SkyWars, Trickster, Chemist, Paladin, Samurai, Sprinter, Knockback, Warlock. No kit has Fire Aspect. Abilities were nerfed by about half; `abilities-enabled: false` in `config.yml`
removes every ability item. Maps: Colosseum, Sky Islands, Castle, Wasteland, **Nether Bastion, Frozen Lake, Sumo Ring**.

## New in this version

- **Auto-built maps**: every duel / queue match gets a *fresh* map (Colosseum, Sky Islands, Castle or Wasteland) built in a
  dedicated void world and wiped afterwards - no arena setup needed (`matches.auto-build` in `config.yml`).
- **Queue**: `/queue 1v1 classic ranked`, `/queue 2v2`, `/queue 3v3` (or the nether-star hotbar item). Ranked 1v1 uses an Elo window that widens over time.
- **Teams & parties**: `/party invite <player>`, then queue as a party or challenge another party leader (2v2 / 3v3), friendly fire off.
- **Best-of rounds**: duels default to best-of-3 (1 / 3 / 5 in the setup menu), arena restored between rounds.
- **Hub hotbar items**: compass = menu, nether star = queue, sword = FFA, book = stats, ender chest = cosmetics, head = friends, name tag = party.
- **Friends**: `/friend add|remove|accept|list` + a friends menu with one-click duels and party invites.
- **Cosmetics**: pick your own kill effect, victory fireworks and hit particles (all defined in `config.yml`).
- **Kit creator**: set up your inventory and run `/duel admin kit create <id>`.
- **Leaderboard holograms**: `/duel admin board create <wins|kills|elo|season_wins|season_kills|kit:<id>>`, plus seasons (`/duel admin season new confirm`).
- **PlaceholderAPI** (optional): `%duelforge_wins%`, `%duelforge_elo%`, `%duelforge_top_wins_1_name%` ... (see `DuelForgeExpansion`).

## Features

| Area | What you get |
|------|--------------|
| **Kits** | Classic, UHC, OP, Archer, Tank, Ninja, Axe – every kit is fully defined in `kits.yml` (items, armor, enchants, effects, abilities, regen / hunger / fall-damage rules) |
| **Duels** | `/duel <player>`, `/accept`, `/deny`, `/duel leave`, kit + arena + ranked choice, countdown, clickable chat invites, rematch |
| **FFA** | Lobbies with lives, auto-start timer, respawn protection, last-player-standing |
| **Arenas** | Cuboid bounds, multiple spawns, spectator spawn, escape prevention (move + teleport + pearls), **automatic block restore** after each match |
| **Combat** | Kill feed, kill streaks, kill/victory effects, hit sounds, per-kit abilities with cooldowns, void kills, no item drops, no inventory exploits, command blocking |
| **Stats** | Wins, losses, kills, deaths, kill streak, win streak, **Elo** (ranked 1v1), saved to `stats.yml` (atomic, async) |
| **GUI** | Hub, player picker, setup menu, kit browser, kit **preview** (armor, items, abilities), arena picker, invitations, stats + leaderboard, FFA lobbies, spectate list, **kit layout editor** |
| **Safety** | Player inventory/location/effects are snapshotted to disk during matches and restored on quit, shutdown, **or after a crash** |

## Build

```bash
mvn package
# -> target/DuelForge-1.0.0.jar
```

Drop the jar into `plugins/`, start the server once, then set up an arena.

## Quick start

1. Stand on flat ground where you want the arena and run
   `/duel admin arena quick arena1 15` – instantly creates a cube arena with 4 spawns.
   (Or build it manually: `create`, `pos1`, `pos2`, `addspawn` ×2+, `spectator`.)
2. `/duel` opens the menu. `/duel <friend>` starts a challenge.
3. Optional: `/duel admin setlobby` and set `lobby.return-to-lobby: true` in `config.yml`.

## Protected world, build mode and the lobby world

- **World protection** (on by default): nobody can break or place blocks until a duel starts. Inside a running duel,
  blocks can be broken/placed **only inside the arena**, and everything is restored afterwards.
  PvP outside matches, hunger and explosion damage are also disabled outside matches (see `world-protection:` in `config.yml`).
- **Build mode**: `/duel admin build` toggles your personal permission to build outside duels. It resets when you leave.
- **Custom lobby world**: `/duel admin world create lobby` makes an empty void world with a small platform,
  teleports you there, sets it as the lobby and turns build mode on, so you can build your spawn.
  Use `/duel admin world create lobby flat` for a flat world instead. Worlds are remembered in `worlds.yml`.
  Players then spawn at the lobby on join/respawn and return to it after matches. `/duel lobby` teleports back to it.
  You can also build in your normal world and just run `/duel admin setlobby`.

## One-command PvP world (hub + 4 maps)

```
/duel admin autosetup
```
Creates a void world called `pvp` and builds, on its own: a quartz **hub** with lamp posts, four ready-made arenas -
**Colosseum**, **Sky Islands**, **Castle** and **Wasteland** - and **bridges** from the hub to each of them.
It registers the arenas and sets the hub as the lobby. Players spawn in the hub, can walk anywhere (the bridges lead into
the arenas), and are returned to the hub after each duel - nobody ever has to leave the world. The arena boundary only
applies while you are *in a fight*.

Build any single map wherever you stand (the floor is the block under your feet; best done in a void or flat world):

```
/duel admin arena generate <colosseum|sky|castle|wasteland> [name]
```

## Commands

| Command | Description |
|---------|-------------|
| `/duel` | Main menu |
| `/duel <player> [kit] [arena\|random] [ranked]` | Challenge (opens the setup GUI when kit is omitted) |
| `/accept [player]`, `/deny [player]` | Answer a challenge (also clickable in chat) |
| `/duel leave` | Leave a match, lobby, or stop spectating |
| `/duel ffa [create]`, `/duel join <host>`, `/duel start` | Free-for-all lobbies |
| `/duel spectate [player]` | Watch a match |
| `/duel rematch` | Challenge your last opponent again |
| `/duel kits`, `/duel kit <id>`, `/duel editkit` | Browse / preview / edit kits |
| `/duel invites` | Pending invitations menu |
| `/duelstats [player\|top [wins\|kills\|elo]]` | Statistics |
| `/duel admin reload` | Reload config, kits, messages |
| `/duel admin arena <create\|quick\|delete\|pos1\|pos2\|addspawn\|clearspawns\|spectator\|toggle\|building\|icon\|list\|info\|tp> [name]` | Arena management |
| `/duel admin setlobby`, `build`, `world <create\|tp\|list> [name] [void\|flat]`, `end [player]`, `resetstats <player>` | Misc admin |
| `/duel admin autosetup`, `arena generate <theme> [name]` | Auto-build the PvP world / a single map |
| `/duel lobby` | Teleport to the lobby |
| `/queue [1v1\|2v2\|3v3] [kit] [ranked] [bo1\|bo3\|bo5]`, `/queue leave` | Matchmaking queue |
| `/party <invite\|accept\|deny\|leave\|kick\|disband\|list>` | Parties for team duels |
| `/friend <add\|remove\|accept\|deny\|list>` | Friends list |
| `/duel cosmetics` | Cosmetics menu |
| `/duel admin kit <create\|delete\|icon\|name\|desc\|setting\|list> <id>` | Make kits in-game |
| `/duel admin board <create\|remove\|refresh> [metric]`, `season new confirm` | Leaderboard holograms and seasons |

## Permissions

`duelforge.use`, `.duel`, `.ffa`, `.ranked`, `.spectate`, `.editkit`, `.stats` (all default **true**);
`duelforge.admin`, `duelforge.bypass.commands` (default **op**).
Each kit can additionally require a permission via `permission:` in `kits.yml`.

## Configuration files

- `config.yml` – timers, lives, Elo, sounds, particles, ability values and cooldowns
- `kits.yml` – kits (slots 0-8 hotbar, 9-35 inventory; `helmet/chestplate/leggings/boots/offhand`)
- `arenas.yml` – written by the arena commands
- `messages.yml` – every message, GUI label and scoreboard line (supports `&` and `&#RRGGBB`)
- `stats.yml`, `layouts.yml`, `snapshots/` – data (generated)

### Adding a kit

```yaml
kits:
  sumo:
    display-name: "&e&lSumo"
    icon: LEAD
    settings: {natural-regen: true, hunger: false, fall-damage: false}
    effects: [{type: resistance, level: 5}]
    items:
      - {slot: 0, item: STICK, enchants: {knockback: 2}}
```

Abilities (`ability: dash | smoke_bomb | slam | leap | berserk | thunder | golden_head`) are tuned in `config.yml`.

## Compatibility notes

Built against Spigot API 1.20.4. Enchantments, potion effects, sounds and particles are resolved by name at runtime so
the same jar is intended to work on newer Paper/Spigot versions as well.

## Publishing to GitHub

```bash
git init
git add .
git commit -m "Initial commit: DuelForge"
git branch -M main
git remote add origin https://github.com/<you>/DuelForge.git
git push -u origin main
```

The included GitHub Actions workflow (`.github/workflows/build.yml`) builds the jar on every push and uploads it as an artifact.

## License

MIT
