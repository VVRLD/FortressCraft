# Installing FortCraft on Windows

## 1. Get these first

- **Steam**, signed in, with **Team Fortress 2** and **Source SDK Base 2013 Multiplayer**
  installed (the SDK Base is free: Steam > Library > Tools).
- **Minecraft: Java Edition.**
- **Visual Studio 2022 or newer** (Community is free) or **Build Tools for Visual Studio**,
  with the workload **"Desktop development with C++"**.
- **Java 25** and **Git**: setup offers to install both for you (with winget) if they're missing.
- About 6 GB of free disk space and an internet connection.

## 2. Set up (once)

1. Unzip the Windows release somewhere you can write to, for example `Documents\FortCraft`.
   Keep the folders together.
2. Double-click **`START-HERE.cmd`**.

Setup finds Java 25, Visual Studio and your Steam games, downloads Valve's Source SDK 2013 next
to the `my-passthrough` folder, applies FortCraft's changes, builds the TF2 side, installs the
test map and prepares the Minecraft side. The first run takes a while (roughly 10-30 minutes).
If it stops, the red message says what to fix; run `START-HERE.cmd` again afterwards and it
carries on.

## 3. Play

Double-click **`PLAY-FORTCRAFT.cmd`**. Minecraft opens a world with normal terrain and mobs,
and TF2 starts hidden in the background. Close Minecraft to stop; TF2 closes by itself.

- Keys: [CONTROLS.md](CONTROLS.md).
- Flat test world instead: `my-passthrough\tools\play.bat`.
- One of your own worlds: copy its folder into `my-passthrough\fabric\run\saves`, then in a
  Command Prompt run `set FORTCRAFT_WORLD=<folder name>` followed by `my-passthrough\tools\play.bat`.

## If something goes wrong

- Logs are in `my-passthrough\logs`, Minecraft's in `my-passthrough\fabric\run\logs`, TF2's
  console in `source-sdk-2013\game\mod_tf\console.log`.
- "TF2 is still running": close Minecraft, wait a few seconds, try again.
- Steam must be running and signed in.
