#!/usr/bin/env bash
# Linux: starts Minecraft (with the FortCraft mod) and our hidden TF2 mod together.
# Build the TF2 mod first with tools/build_tf2.sh (play.bat builds every time on Windows; here
# the build runs in a container, so it is a separate step).
# To stop: close Minecraft. TF2 closes by itself a moment later.
#   tools/play.sh                      flat test world
#   tools/play.sh -fortcraft_offscreen  extra switches go to TF2 (see tools/run_tf2.sh)
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
LOGS="$HERE/../logs"
mkdir -p "$LOGS"

chmod +x "$HERE/../fabric/gradlew" 2>/dev/null || true
# Gradle needs Java 25 (newer Java such as 27 makes the Minecraft build fail at once).
source "$HERE/java25.sh"
echo "Starting Minecraft (log: logs/minecraft_stdout.log). The first start downloads Minecraft and Fabric files."
( cd "$HERE/../fabric" && ./gradlew runClient > "$LOGS/minecraft_stdout.log" 2>&1 ) &
MC=$!

"$HERE/run_tf2.sh" "$@" &
TF2=$!

# Wait for Minecraft; TF2 notices the link is gone and quits by itself.
wait "$MC" || true
echo "Minecraft closed. Waiting a few seconds for TF2 to quit..."
for _ in 1 2 3 4 5 6 7 8 9 10; do
	kill -0 "$TF2" 2>/dev/null || break
	sleep 1
done
if kill -0 "$TF2" 2>/dev/null; then
	echo "TF2 is still running; stopping it."
	kill "$TF2" 2>/dev/null || true
fi
