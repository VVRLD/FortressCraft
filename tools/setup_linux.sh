#!/usr/bin/env bash
# Linux, one time: fetch Valve's Source SDK 2013 next to my-passthrough and apply FortCraft's
# changes to it. Safe to run again: it stops if the SDK folder already exists.
#
#   tools/setup_linux.sh
#
# Afterwards: tools/build_tf2.sh, then tools/play.sh.
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$HERE/../.." && pwd)
SDK="$ROOT/source-sdk-2013"
BASE=b8cfb12c0e083a2ef5b2f9f9b50f3902fa034474   # Valve's commit our patch is made against

chmod +x "$HERE"/*.sh "$HERE/../fabric/gradlew" 2>/dev/null || true

# FortCraft's changes to Valve's SDK refer to this folder as "my-passthrough" next to
# "source-sdk-2013". A GitHub clone may have another name, so link that name to it.
PROJECT=$(cd -- "$HERE/.." && pwd)
if [[ "$(basename -- "$PROJECT")" != "my-passthrough" ]]; then
	if [[ ! -e "$ROOT/my-passthrough" ]]; then
		ln -s "$(basename -- "$PROJECT")" "$ROOT/my-passthrough"
		echo "Linked $ROOT/my-passthrough to this folder (the SDK changes expect that name)."
	elif [[ "$(readlink -f -- "$ROOT/my-passthrough")" != "$PROJECT" ]]; then
		echo "$ROOT/my-passthrough already exists and is a different folder."
		echo "Move this folder somewhere else, or rename it to my-passthrough."
		exit 1
	fi
fi

missing=()
for tool in git podman; do
	command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
done
if (( ${#missing[@]} )); then
	echo "Please install first: ${missing[*]}"
	exit 1
fi
# Minecraft's build needs Java 25 exactly; find it now (downloads a private copy if needed).
source "$HERE/java25.sh"

if [[ -e "$SDK" ]]; then
	echo "$SDK already exists; leaving it alone."
else
	echo "Downloading Valve's Source SDK 2013 into $SDK ..."
	git clone https://github.com/ValveSoftware/source-sdk-2013.git "$SDK"
	git -C "$SDK" checkout -q "$BASE"
	git -C "$SDK" apply --check "$HERE/../tf2mod/sdk-changes.patch"
	git -C "$SDK" apply "$HERE/../tf2mod/sdk-changes.patch"
	echo "FortCraft's changes applied to the SDK."
fi

if [[ -x "$SDK/game/mod_tf_linux64" && -f "$SDK/game/mod_tf/bin/linux64/client.so" ]]; then
	echo
	echo "The TF2 side is already built. After updating FortCraft, rebuild it with tools/build_tf2.sh."
else
	echo
	echo "Building the TF2 side (first time: downloads Valve's build container, then compiles for a while)..."
	"$HERE/build_tf2.sh"
fi

echo
echo "Setup finished. Start FortCraft with ./play.sh (normal world) or tools/play.sh (flat test world)."
