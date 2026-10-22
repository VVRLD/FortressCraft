# Installing FortCraft on Linux

Linux support is new and less tested than Windows. If something fails, please report it with the
file from `tools/collect_logs.sh` (see the end of this page).

## 1. Get these first

- A 64-bit Linux desktop (X11 or Wayland).
- **Steam**, signed in and running, with **Team Fortress 2** and **Source SDK Base 2013
  Multiplayer** installed (the SDK Base is free: Steam > Library > Tools), on no beta branch
  (Properties > Betas: None; old branches like previous2021 lack its 64-bit files, #10). On a Steam Deck or
  any system where TF2's engine won't load on its own, also **Steam Linux Runtime 3.0 (sniper)**
  (Library > Tools).
- **git** and **podman**:
  - Ubuntu / Debian / Mint: `sudo apt install git podman`
  - Fedora: `sudo dnf install git podman`
  - Arch: `sudo pacman -S git podman`
- About 6 GB of free disk space and an internet connection.

You don't need to install Java: Minecraft's build needs exactly Java 25, and setup uses yours if
you have it or downloads a private copy (Eclipse Temurin 25) into `~/.cache/fortcraft`. Your
system Java isn't changed.

## 2. Set up (once)

Unzip the Linux release (or clone the repository), open a terminal in that folder and run:

```bash
bash setup.sh
```

Setup downloads Valve's Source SDK 2013 next to the FortCraft folder, applies FortCraft's
changes, then builds the TF2 side inside Valve's own build container (Steam Runtime "sniper",
run with podman). The first run downloads that container (1-2 GB) and compiles for 10-40
minutes. Running `bash setup.sh` again later skips what's already done.

At the end, setup offers to open the page of **mvm_coaltown**, a free Minecraft map that the
Ghost Town's Mann vs. Machine waves are played on. FortCraft can't include it because it's someone
else's map. Download it in your browser, then press Enter in setup and it installs the zip
from your Downloads folder. You can skip this; the Ghost Town then has no waves.

After updating FortCraft, rebuild the TF2 side with `tools/build_tf2.sh`.

## 3. Play

```bash
./play.sh
```

Minecraft opens a world with normal terrain and mobs, and TF2 starts hidden in the background.
Close Minecraft to stop; TF2 closes by itself. The first start downloads Minecraft and Fabric
files.

- Keys: [CONTROLS.md](CONTROLS.md).
- Flat test world instead: `tools/play.sh`.
- One of your own worlds: copy its folder into `fabric/run/saves`, then
  `FORTCRAFT_WORLD="<folder name>" tools/play.sh`.

## If something goes wrong

- **No TF2 weapon or HUD, or TF2 seems frozen:** close Minecraft and try
  `tools/play.sh -fortcraft_offscreen` (keeps TF2's window open but off-screen instead of hidden).
- **See TF2's own window:** run `tools/run_tf2.sh visible` in one terminal, then
  `source tools/java25.sh && cd fabric && ./gradlew runClient` in another.
- **Minecraft ignores the keyboard after starting:** click once inside its window.
- **"Unable to load module engine.so!" (seen on a Steam Deck):** TF2's engine needs libraries
  from Steam's Linux Runtime. Install **Steam Linux Runtime 3.0 (sniper)** (Steam > Library >
  Tools) and run `tools/play.sh` again: it then starts TF2 inside that runtime by itself. What it
  found is in `logs/tf2_libraries.txt`.
- **Steam Deck:** set up and play from Desktop Mode, with Steam running.
- **Report a problem:** run `tools/collect_logs.sh`. It writes
  `FortCraft-linux-logs-<date>.tar.gz` next to the FortCraft folder with the game logs and basic
  system facts (distro, X11/Wayland, GPU, Java and podman versions) — no saves or account details.
  Attach it to your report with a short description of what happened.
