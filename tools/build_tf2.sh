#!/usr/bin/env bash
# Linux: builds our TF2 mod (client.so, server.so and the mod_tf_linux64 launcher) with Valve's own
# Linux route: src/buildallprojects inside the Steam Runtime "sniper" container, run by podman.
# Output lands in ../../source-sdk-2013/game/mod_tf/bin/linux64 (launcher: game/mod_tf_linux64).
#
#   tools/build_tf2.sh            release build (run this before play.sh)
#   tools/build_tf2.sh debug      debug build
#   tools/build_tf2.sh --regen    regenerate the build files first (after a .vpc change)
#
# TF2 must not be running. The first run downloads the container image (about 1-2 GB).
#
# Valve's own script mounts only source-sdk-2013 into the container, but our code lives next
# to it in my-passthrough. So we mount the folder that holds both and start Valve's script
# inside the container ourselves.
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$HERE/../.." && pwd)          # holds my-passthrough/ and source-sdk-2013/
SRC="$ROOT/source-sdk-2013/src"
IMAGE="registry.gitlab.steamos.cloud/steamrt/sniper/sdk:latest"
LOGS="$HERE/../logs"
mkdir -p "$LOGS"

mode=release
for arg in "$@"; do
	case "$arg" in
		debug|release) mode="$arg" ;;
		--regen) rm -rf "$SRC/_vpc_/ninja" && echo "Build files will be regenerated." ;;
		*) echo "Usage: $0 [debug|release] [--regen]"; exit 1 ;;
	esac
done

if [[ ! -x "$SRC/buildallprojects" ]]; then
	chmod +x "$SRC/buildallprojects" "$SRC/devtools/bin/vpc" 2>/dev/null || true
fi
for f in "$SRC/buildallprojects" "$SRC/sdk_container" "$SRC/devtools/bin/vpc"; do
	[[ -e "$f" ]] || { echo "Missing $f. Is the SDK checked out next to my-passthrough?"; exit 1; }
done
if ! command -v podman >/dev/null 2>&1; then
	echo "podman is not installed. Install it with your package manager (for example: sudo apt install podman)."
	exit 1
fi
if pgrep -f 'mod_tf_linux64' >/dev/null 2>&1; then
	echo "TF2 (mod_tf) is still running. Close Minecraft, wait a few seconds, and try again."
	exit 1
fi

# Valve's script checks this variable to know it is already inside the container.
check_env=$(sha256sum -- "$SRC/buildallprojects" | cut -d " " -f 1)
ccache="${CCACHE_DIR:-$HOME/.ccache}"
mkdir -p "$ccache"
tty=()
[[ -t 0 ]] && tty=(-it)

echo "Building the TF2 mod ($mode) inside $IMAGE ..."
set +e  # keep going after a failed build so the log path is printed
podman run --rm ${tty[@]+"${tty[@]}"} \
	--userns=keep-id \
	--env "$check_env=buildallprojects" \
	--env "CCACHE_DIR=$ccache" \
	--mount "type=bind,source=$ccache,target=$ccache" \
	--mount "type=bind,source=$ROOT,target=/my_mod" \
	"$IMAGE" \
	/my_mod/source-sdk-2013/src/buildallprojects "$mode" 2>&1 | tee "$LOGS/build_tf2.log"
status=${PIPESTATUS[0]}
set -e
if (( status != 0 )); then
	echo "Build FAILED (exit $status). The full log is in logs/build_tf2.log; please send it back (tools/collect_logs.sh)."
	exit "$status"
fi

echo
echo "Built files:"
ls -la "$ROOT/source-sdk-2013/game/mod_tf/bin/linux64/" 2>/dev/null || echo "  (no game/mod_tf/bin/linux64 folder: the build did not install client.so/server.so)"
ls -la "$ROOT/source-sdk-2013/game/mod_tf_linux64" 2>/dev/null || echo "  (no game/mod_tf_linux64 launcher found)"
echo "TF2 mod build finished."
