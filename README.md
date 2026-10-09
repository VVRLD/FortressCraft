# FortCraft

Play Minecraft as a Team Fortress 2 mercenary. Minecraft draws the world and keeps its blocks,
mobs, dimensions and saves; a locally built TF2 mod runs hidden alongside it and supplies TF2's
movement, classes, weapons, buildings, menus and HUD.

**Experimental, single-player and offline only.** Never use it on public TF2 servers. Back up
your Minecraft worlds before playing.

No game files are included. You need your own Team Fortress 2 and Source SDK Base 2013
Multiplayer (both free on Steam) and Minecraft: Java Edition. Setup downloads Valve's open
Source SDK 2013, applies FortCraft's changes, and builds everything on your own computer.

## Install

| | Windows 10/11 | Linux |
|---|---|---|
| Guide | [INSTALL-WINDOWS.md](INSTALL-WINDOWS.md) | [INSTALL-LINUX.md](INSTALL-LINUX.md) |
| Setup (once) | double-click `START-HERE.cmd` | `bash setup.sh` |
| Play | double-click `PLAY-FORTCRAFT.cmd` | `./play.sh` |

The easiest way is the ready-to-use zip for your system on the **Releases** page. Cloning works
too; setup takes care of the folder layout either way.

## Controls

See [CONTROLS.md](CONTROLS.md): keys, TF2 menus, the backpack with your Minecraft items, health
and ammo packs, and crafting. TF2's keys can be rebound in Minecraft's Controls menu under
"FortCraft (TF2)".

## What works

TF2 classes, weapons, HUD and menus; rocket and sticky jumps on Minecraft blocks; shooting and
blowing up Minecraft mobs; melee block breaking with Minecraft cracks and drops; Engineer
buildings that fight hostile mobs; TF2's backpack showing your Minecraft items (equip blocks to
hand, use health and ammo packs); crafting Minecraft recipes and supply packs in TF2's crafting
screen; taunts, voice commands, the TF2 console; the Nether and the End.

## Known limitations

- Experimental: not every TF2 weapon, projectile or cosmetic has been checked against every mob.
- Medic's healing beam on Minecraft animals doesn't work yet.
- Linux is new and less tested than Windows; please report problems with the logs from
  `tools/collect_logs.sh`.
- Minecraft 26.3 with Fabric Loader 0.19.5 only; other versions are not supported.

## How it works (short)

The two games share a block of memory. Minecraft sends its blocks, mobs and your keys; TF2
moves you, runs the weapons, draws its weapon and HUD into a picture Minecraft shows on top of
its own, and reports hits back to Minecraft. Code: `fabric/` (the Minecraft mod), `tf2mod/` (the
TF2 side and `sdk-changes.patch` for Valve's SDK), `protocol/` (the shared memory layout),
`tools/` (setup, build and launch scripts).

## Licence

FortCraft's own code is MIT-licensed ([LICENSE](LICENSE)). Valve's SDK and FortCraft's changes
to it fall under Valve's Source 1 SDK licence; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
FortCraft is an unofficial fan project, not affiliated with or endorsed by Valve, Mojang or
Microsoft.
