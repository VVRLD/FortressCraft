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
CFG
SHORTLOGS="/tmp/fortcraft-logs-$(id -u)"
ln -sfn "$LOGS" "$SHORTLOGS"

cd "$GAME"
echo "Starting TF2 (log: $GAME/mod_tf/console.log and $LOGS/tf2_stdout.log)"
exec ./mod_tf_linux64 -novid -windowed -w 1280 -h 720 -insecure -condebug ${HIDE[@]+"${HIDE[@]}"} \
	-fortcraft_logs "$SHORTLOGS" "$@" +exec fortcraft_launch +map fortcraft_flat \
	> "$LOGS/tf2_stdout.log" 2>&1
