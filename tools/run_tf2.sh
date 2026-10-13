#!/usr/bin/env bash
# Linux: starts our TF2 mod (built by tools/build_tf2.sh) on our own empty map, offline.
# Same switches as tools/run_tf2.bat:
#   -insecure        VAC off (TF2's own option); +sv_lan 1: LAN only, no internet players
#   +fps_max 144     cap TF2's frame rate until it learns Minecraft's (then it matches it)
#   +snd_mute_losefocus 0  keep TF2's sound on while its window is hidden
#   +mat_queue_mode 0  draw on the main thread
#   +mp_waitingforplayers_time 0 etc.: no "waiting for players" round reset and no round end
#   -fortcraft_hidden  hide TF2's window; it closes by itself when Minecraft closes
# Pass "visible" first to keep TF2's window on screen:  tools/run_tf2.sh visible
# Extra switches go after it (or alone):
#   -fortcraft_offscreen    Linux: keep TF2's window shown but move it off-screen instead of hiding it
#                           (try this if TF2 freezes or the weapon/HUD never appears while hidden)
#   -fortcraft_no_overlay   no weapon/HUD copy at all
# Steam must be running and signed in; TF2 and Source SDK Base 2013 Multiplayer must be installed.
# If the system can't load TF2's engine by itself (Steam Deck), it runs inside Steam's Linux
# Runtime 3.0 (sniper). FORTCRAFT_STEAM_RUNTIME=1 always does, =0 never does. See
# logs/tf2_libraries.txt for what was found.
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
GAME=$(cd -- "$HERE/../../source-sdk-2013/game" && pwd)
LOGS="$HERE/../logs"
mkdir -p "$LOGS"
LOGS=$(cd -- "$LOGS" && pwd)

if [[ ! -x "$GAME/mod_tf_linux64" ]]; then
	echo "No TF2 mod launcher at $GAME/mod_tf_linux64. Run tools/build_tf2.sh first."
	exit 1
fi
# Our empty map is built on Windows (Valve ships no Linux map compiler). The tester package
# carries a copy in prebuilt/; install it once.
MAP="$GAME/mod_tf/maps/fortcraft_flat.bsp"
if [[ ! -f "$MAP" ]]; then
	for candidate in "$HERE/../prebuilt/fortcraft_flat.bsp" "$HERE/../../prebuilt/fortcraft_flat.bsp"; do
		if [[ -f "$candidate" ]]; then
			mkdir -p "$GAME/mod_tf/maps"
			cp "$candidate" "$MAP"
			echo "Installed the FortCraft map into $MAP"
			break
		fi
	done
fi
[[ -f "$MAP" ]] || { echo "Missing $MAP (copy prebuilt/fortcraft_flat.bsp there)."; exit 1; }

HIDE=(-fortcraft_hidden)
if [[ "${1:-}" == "visible" ]]; then
	HIDE=()
	shift
fi

# Source refuses a command line over 512 characters ("command line too long, 512 max"), and the
# Linux launcher adds the full game path to it. So the console settings go in a small config file
# (run with +exec) and the log folder is passed through a short link in /tmp.
mkdir -p "$GAME/mod_tf/cfg"
cat > "$GAME/mod_tf/cfg/fortcraft_launch.cfg" <<'CFG'
// Written by FortCraft's tools/run_tf2.sh on every start; same settings as tools/run_tf2.bat.
sv_lan 1
engine_no_focus_sleep 0
snd_mute_losefocus 0
mat_queue_mode 0
fps_max 144
mp_waitingforplayers_time 0
mp_timelimit 0
mp_winlimit 0
mp_maxrounds 0
exec fortcraft_graphics
CFG
# The player's own TF2 graphics settings (motion blur and vertical sync always off).
python3 "$HERE/tf2_graphics.py" "$GAME/mod_tf/cfg/fortcraft_graphics.cfg" \
	|| echo "Couldn't copy your TF2 graphics settings; TF2 keeps its own."
SHORTLOGS="/tmp/fortcraft-logs-$(id -u)"
ln -sfn "$LOGS" "$SHORTLOGS"

# TF2's engine comes from Steam's "Source SDK Base 2013 Multiplayer" (app 243750): the launcher
# below runs its hl2.sh. On a Steam Deck (2026-10-11) it failed with "Unable to load module
# engine.so!": the engine needs libraries from Steam's Linux Runtime, which SteamOS doesn't have
# outside it. So: find the SDK Base and the "sniper" runtime in Steam's libraries, check that
# engine.so's libraries can all be found, and if not, run inside the runtime.
steam_libraries() {
	local root
	for root in "$HOME/.steam/steam" "$HOME/.local/share/Steam" "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam"; do
		[[ -d "$root/steamapps" ]] || continue
		echo "$root"
		grep -o '"path"[[:space:]]*"[^"]*"' "$root/steamapps/libraryfolders.vdf" 2>/dev/null | sed 's/.*"\([^"]*\)"$/\1/'
	done | awk '!seen[$0]++'
}
SDKBASE=""
SNIPER=""
while IFS= read -r lib; do
	if [[ -z "$SDKBASE" && -f "$lib/steamapps/appmanifest_243750.acf" ]]; then
		SDKBASE="$lib/steamapps/common/Source SDK Base 2013 Multiplayer"
	fi
	if [[ -z "$SNIPER" && -x "$lib/steamapps/common/SteamLinuxRuntime_sniper/run" ]]; then
		SNIPER="$lib/steamapps/common/SteamLinuxRuntime_sniper"
	fi
done < <(steam_libraries)
DIAG="$LOGS/tf2_libraries.txt"
{
	echo "Source SDK Base 2013 Multiplayer: ${SDKBASE:-not found}"
	echo "Steam Linux Runtime 3.0 (sniper): ${SNIPER:-not found}"
} > "$DIAG"
if [[ -z "$SDKBASE" ]]; then
	echo "Steam's \"Source SDK Base 2013 Multiplayer\" isn't installed. In Steam: Library, Tools,"
	echo "install \"Source SDK Base 2013 Multiplayer\", then run this again."
	exit 1
fi
ENGINE="$SDKBASE/bin/linux64/engine.so"
if [[ ! -f "$ENGINE" ]]; then
	echo "Missing $ENGINE."
	echo "In Steam: right-click Source SDK Base 2013 Multiplayer, Properties, Installed Files,"
	echo "Verify integrity of game files; then run this again."
	exit 1
fi
MISSING=$(LD_LIBRARY_PATH="$SDKBASE/bin/linux64:$SDKBASE/bin" ldd "$ENGINE" 2>/dev/null | grep "not found" || true)
echo "engine.so libraries missing outside Steam's runtime: ${MISSING:-none}" >> "$DIAG"
RUNTIME=()
if [[ -n "$MISSING" || "${FORTCRAFT_STEAM_RUNTIME:-}" == "1" ]] && [[ "${FORTCRAFT_STEAM_RUNTIME:-}" != "0" ]]; then
	if [[ -z "$SNIPER" ]]; then
		echo "TF2's engine needs libraries this system doesn't have:"
		echo "$MISSING"
		echo "Install Steam's \"Steam Linux Runtime 3.0 (sniper)\" (Library, Tools), then run this again."
		exit 1
	fi
	RUNTIME=("$SNIPER/run" --)
	# The container only sees the usual folders: share the Source SDK Base library and the
	# FortCraft folder in case they're on another drive (GitHub issue 11: /mnt/... made TF2 say
	# Source SDK Base 2013 Multiplayer isn't installed).
	SDKLIB=$(cd "$SDKBASE/../../.." && pwd)
	export PRESSURE_VESSEL_FILESYSTEMS_RW="${PRESSURE_VESSEL_FILESYSTEMS_RW:+$PRESSURE_VESSEL_FILESYSTEMS_RW:}$SDKLIB:$(cd "$GAME/../.." && pwd)"
	echo "Shared with Steam's runtime: $PRESSURE_VESSEL_FILESYSTEMS_RW" >> "$DIAG"
	echo "Running TF2 inside Steam's Linux Runtime ($SNIPER)"
fi
echo "Launch: ${RUNTIME[*]:-direct}" >> "$DIAG"

cd "$GAME"
echo "Starting TF2 (log: $GAME/mod_tf/console.log and $LOGS/tf2_stdout.log)"
exec ${RUNTIME[@]+"${RUNTIME[@]}"} ./mod_tf_linux64 -novid -windowed -w 1280 -h 720 -insecure -condebug ${HIDE[@]+"${HIDE[@]}"} \
	-fortcraft_logs "$SHORTLOGS" "$@" +exec fortcraft_launch +map fortcraft_flat \
	> "$LOGS/tf2_stdout.log" 2>&1
