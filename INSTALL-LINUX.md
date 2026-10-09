# Installing FortCraft on Linux

Linux support is new and less tested than Windows. If something fails, please report it with the
file from `tools/collect_logs.sh` (see the end of this page).

## 1. Get these first

- A 64-bit Linux desktop (X11 or Wayland).
- **Steam**, signed in and running, with **Team Fortress 2** and **Source SDK Base 2013
  Multiplayer** installed (the SDK Base is free: Steam > Library > Tools).
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
- **Report a problem:** run `tools/collect_logs.sh`. It writes
  `FortCraft-linux-logs-<date>.tar.gz` next to the FortCraft folder with the game logs and basic
  system facts (distro, X11/Wayland, GPU, Java and podman versions) — no saves or account details.
  Attach it to your report with a short description of what happened.
