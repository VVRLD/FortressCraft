# FortCraft — source preview

Play Minecraft as a Team Fortress 2 mercenary. Minecraft draws the world and owns its blocks, mobs, dimensions, and saves; a locally built TF2 bridge supplies the player, weapons, classes, projectiles, buildings, menus, and HUD.

FortCraft is an experimental Windows-only, single-player/offline passthrough mod. It is a developer/source preview, not a one-click installer or a public multiplayer mod. Back up Minecraft worlds before testing.

No TF2 or Minecraft game files, saves, compiled game binaries, or complete Source SDK checkout are included. Every user supplies their own legitimate installations.

## Requirements

- Windows 11
- Team Fortress 2 installed through Steam
- Source SDK Base 2013 Multiplayer installed through Steam
- Minecraft Java Edition
- Git
- JDK 25
- Visual Studio with Desktop development with C++ and MSBuild
- PowerShell and internet access for Fabric/Gradle dependencies

The included Fabric project currently targets Minecraft `26.3`, Fabric Loader `0.19.5`, and Java 25. Other Minecraft versions are not supported by this source preview.

## Setup

Keep the folder names in this layout. The SDK patch expects `my-passthrough` beside `source-sdk-2013`:

```text
FortCraft-folder/
  my-passthrough/       <- this repository
  source-sdk-2013/      <- clone Valve's SDK here
```

Open PowerShell in `FortCraft-folder` and obtain the separately licensed Source SDK:

```powershell
git clone https://github.com/ValveSoftware/source-sdk-2013.git source-sdk-2013
git -C source-sdk-2013 checkout b8cfb12c0e083a2ef5b2f9f9b50f3902fa034474
git -C source-sdk-2013 apply --check my-passthrough/tf2mod/sdk-changes.patch
git -C source-sdk-2013 apply my-passthrough/tf2mod/sdk-changes.patch
```

The launch scripts use your local Steam and Visual Studio locations. Edit the path variables in `tools/build_map.bat`, `tools/build_tf2.bat`, and `tools/run_tf2.bat` if your installations are in non-standard folders.

Generate the SDK projects, then build and launch:

```powershell
cd source-sdk-2013/src
./createallprojects.bat
cd ../../my-passthrough
./tools/play_normal.bat
```

The first launch may take a while because Gradle/Fabric downloads and prepares Minecraft development files. The script builds the TF2 client/server DLLs and test map, starts Minecraft, and starts TF2 hidden alongside it. Close Minecraft to end the linked session.

## Launch options

```powershell
./tools/play.bat          # flat FortCraft test world
./tools/play_normal.bat  # normal terrain and mobs
```

Your own Minecraft saves can be copied into `fabric/run/saves`. The normal-terrain launcher can then be pointed at the selected world with `FORTCRAFT_WORLD` as described in `tools/play_normal.bat`.

## Current status and limitations

Built features include TF2 classes, weapons, HUD, menus, backpack block equipment, Engineer buildings, projectiles, explosions, Minecraft mob targeting, survival melee block breaking with cracking, tool-specific block damage, crits, eligible headshots, Spy backstabs, Ender Dragon/End Crystal targeting, and deep-water shoreline assistance.

This is still experimental and not clean-machine tested. Known limitations include:

- Medic's native TF2 healing beam, target panel, and ÜberCharge against Minecraft mobs remain broken.
- The new tool-damage, crit/headshot/backstab, End, and wall-jitter changes still need focused in-game confirmation.
- Some weapons, projectiles, menu interactions, and visual-depth cases remain incomplete.
- A tiny wall-contact jitter may still occur.
- Performance depends on running both games together.
- The launcher is for offline/LAN-oriented testing only. Do not use it on public TF2 servers.

## Source and licences

`fabric/` is the Minecraft-side Fabric mod. `tf2mod/` is the TF2 bridge and SDK patch. `protocol/` defines the shared-memory messages. `tools/` contains setup, build, launch, and test scripts.

Original FortCraft source is MIT-licensed; see [`LICENSE`](LICENSE). The Source SDK patch remains subject to Valve's separate license and notices in [`third_party/source-sdk-2013/`](third_party/source-sdk-2013/) and [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

FortCraft is an unofficial fan project and is not affiliated with or endorsed by Valve, Mojang, or Microsoft.
