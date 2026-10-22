# Installing FortCraft on Windows

## 1. Get these first

- **Steam**, signed in, with **Team Fortress 2** and **Source SDK Base 2013 Multiplayer**
  installed (the SDK Base is free: Steam > Library > Tools). Leave the SDK Base on no beta
  branch (Properties > Betas: None); old branches like previous2021 lack its 64-bit files (#10).
- **Minecraft: Java Edition.**
- **Java 25** and **Git**: setup offers to install them for you (with winget) if they're missing.
- About 3 GB of free disk space and an internet connection.

The TF2 side comes ready-built in the release download, so you don't need Visual Studio or
Python. (Building it yourself from a GitHub checkout still needs **Visual Studio 2022 or newer**
with **"Desktop development with C++"**, and **Python 3**.)

## 2. Set up (once)

1. Unzip the Windows release somewhere you can write to whose path has **no spaces**, for
   example `C:\FortCraft` (not `Desktop\For craft`: Valve's SDK can't be built in a path with a
   space, and setup stops if it finds one). Keep the folders together.
2. Double-click **`START-HERE.cmd`**. Don't run `setup_windows.ps1` directly: Windows often blocks
   PowerShell scripts, and `START-HERE.cmd` works around that.

Setup finds Java 25 and your Steam games, downloads Valve's Source SDK 2013 next to the
`my-passthrough` folder, applies FortCraft's changes, installs the ready-built TF2 side and the
test map, and prepares the Minecraft side. The first run takes a while (roughly 5-15 minutes).
If it stops, the red message says what to fix; run `START-HERE.cmd` again afterwards and it
carries on.

## 3. Play

Double-click **`PLAY-FORTCRAFT.cmd`**. Minecraft opens a world with normal terrain and mobs,
and TF2 starts hidden in the background. Close Minecraft to stop; TF2 closes by itself.

- Keys: [CONTROLS.md](CONTROLS.md).
- Flat test world instead: `my-passthrough\tools\play.bat`.
- One of your own worlds: copy its folder into `my-passthrough\fabric\run\saves`, then in a
  Command Prompt run `set FORTCRAFT_WORLD=<folder name>` followed by `my-passthrough\tools\play.bat`.

### Weaker PC or laptop? Low-end mode

Double-click **`PLAY-FORTCRAFT-LOW-END.cmd`** instead (in the low-end download,
`PLAY-FORTCRAFT.cmd` already does this). Both games run at 60 frames per second instead of
240, TF2 draws at 720p on its lowest graphics, and Minecraft starts with fast graphics, 6 chunks
render distance, fewer particles, and no clouds, shadows or smooth lighting. Minecraft's
settings stay lowered afterwards; raise them again in Minecraft's options if you go back to
the normal launch.

## If something goes wrong

- **Reporting a problem on GitHub?** Double-click **`COLLECT-LOGS.cmd`**. It makes one zip with
  both games' logs and basic facts about your PC (no saves, game files or account details);
  attach it to your issue.
- Logs are in `my-passthrough\logs`, Minecraft's in `my-passthrough\fabric\run\logs`, TF2's
  console in `source-sdk-2013\game\mod_tf\console.log`.
- "TF2 is still running": close Minecraft, wait a few seconds, try again.
- Steam must be running and signed in.
- `error MSB8066 ... exited with code 9009` or `g_Script_... undeclared identifier`: Python 3 is
  missing (or `python` opens the Microsoft Store). Install Python 3 from python.org with "Add
  python.exe to PATH", then run `START-HERE.cmd` again.
- "running scripts is disabled on this system": start setup with `START-HERE.cmd`, not the .ps1 file.
