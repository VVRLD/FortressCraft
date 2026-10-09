# TF2 half

Our TF2 mod is Valve's Source SDK 2013 (TF2 included), cloned next to this project at
`..\..\source-sdk-2013` and never copied into this repo. Our code lives here:

- `fortcraft_link.cpp` / `.h`: the TF2 end of the shared-memory link. Reads Minecraft's keys
  and look, writes TF2's interpolated player position each render frame, picks team and class
  by itself, hides TF2's window with `-fortcraft_hidden`.
- `fortcraft_collision.cpp` / `.h`: Minecraft blocks as solid boxes for TF2 player movement
  (compiled into both client and server).
- `sdk-changes.patch`: our small edits to Valve's files (add our files to the client and
  server projects, call our hooks in player input, render start and player movement traces).

`tools\build_tf2.bat` rebuilds client and server and installs them (TF2 must be closed).

## Set up from scratch

```bat
cd ..\..
git clone --depth 1 https://github.com/ValveSoftware/source-sdk-2013.git
cd source-sdk-2013
git apply ..\my-passthrough\tf2mod\sdk-changes.patch
cd src
createallprojects.bat
```

Build (Visual Studio 2026; Valve targets 2022, so the toolset is overridden):

```bat
"C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\amd64\MSBuild.exe" everything.sln /m /p:Configuration=Release /p:Platform=win64 /p:PlatformToolset=v145
```

`qc_eyes` fails (needs MFC); it's a model tool we don't use. After the first full build,
rebuilding only the client is enough:

```bat
"C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\amd64\MSBuild.exe" game\client\client_win64_tf.vcxproj /m /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v145
```

Needs Source SDK Base 2013 Multiplayer and Team Fortress 2 installed through Steam.

## Run

`tools\play.bat` starts Minecraft and the hidden TF2 together. `tools\run_tf2.bat visible`
starts only TF2 with its window showing.

## Linux

All Windows/Linux differences are in `fortcraft_platform.cpp` (shared memory in
`/dev/shm/FortCraft_v1`, `CLOCK_MONOTONIC`, TF2's SDL2 window). `fortcraft_gpu.cpp` is
Windows-only (Linux stubs). Build with `tools/build_tf2.sh` (podman + Valve's Steam Runtime
container, release by default; `--regen` after a `.vpc` change). Output goes to
`game/mod_tf/bin/linux64/` with the launcher at `game/mod_tf_linux64`. `tools/setup_linux.sh`
clones the SDK and applies `sdk-changes.patch`. Not yet built or run on Linux; see
`../LINUX-TESTING.md`.
