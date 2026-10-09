#!/usr/bin/env bash
# Linux: packs everything FortCraft needs to see from a test run into one file to send back:
# both games' logs, the build log if there is one, and basic system facts (distro, desktop
# session type, GPU, Java/podman versions). No saves, no game files, no account details.
#
#   tools/collect_logs.sh            writes FortCraft-linux-logs-<time>.tar.gz next to my-passthrough
set -uo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT=$(cd -- "$HERE/.." && pwd)
ROOT=$(cd -- "$PROJECT/.." && pwd)
GAME="$ROOT/source-sdk-2013/game"
stamp=$(date +%Y-%m-%d-%H%M%S)
work=$(mktemp -d)
out="$work/fortcraft-logs"
mkdir -p "$out"

{
	echo "date: $(date)"
	echo "distro: $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown}")"
	echo "kernel: $(uname -r)"
	echo "session: XDG_SESSION_TYPE=${XDG_SESSION_TYPE:-?} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:+set} DISPLAY=${DISPLAY:+set}"
	echo "desktop: ${XDG_CURRENT_DESKTOP:-?}"
	echo "gpu:"; (lspci 2>/dev/null | grep -iE 'vga|3d|display') || echo "  (lspci not available)"
	echo "opengl:"; (glxinfo -B 2>/dev/null | grep -iE 'renderer|version') || echo "  (glxinfo not available)"
	echo "java (default): $(java -version 2>&1 | head -1)"
	echo "java 25 for FortCraft: $(ls -d "$HOME/.cache/fortcraft/jdk-25" 2>/dev/null || echo "not downloaded")"
	echo "podman: $(podman --version 2>&1)"
	echo "/dev/shm: $(df -h /dev/shm 2>/dev/null | tail -1)"
	echo "link file: $(ls -la /dev/shm/FortCraft_v1 2>&1)"
	echo "built TF2 files:"; ls -la "$GAME/mod_tf/bin/linux64/" "$GAME/mod_tf_linux64" 2>&1
} > "$out/system.txt"

cp -r "$PROJECT/logs" "$out/fortcraft-logs" 2>/dev/null
cp "$GAME/mod_tf/console.log" "$out/tf2_console.log" 2>/dev/null
cp "$PROJECT/fabric/run/logs/latest.log" "$out/minecraft_latest.log" 2>/dev/null
cp "$PROJECT/fabric/run/logs/debug.log" "$out/minecraft_debug.log" 2>/dev/null
ls "$PROJECT"/fabric/run/hs_err_pid*.log >/dev/null 2>&1 && cp "$PROJECT"/fabric/run/hs_err_pid*.log "$out/" 2>/dev/null
ls "$GAME"/core* >/dev/null 2>&1 && echo "TF2 left a core dump in $GAME (not included; mention it)" >> "$out/system.txt"

dest="$ROOT/FortCraft-linux-logs-$stamp.tar.gz"
tar -czf "$dest" -C "$work" fortcraft-logs
rm -rf "$work"
echo "Wrote $dest"
echo "Please send that file back. Have a quick look first if you want: it holds logs and system facts only."
