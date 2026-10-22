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
	# Only the one version FortCraft needs (much smaller than Valve's whole history), tried up to
	# 4 times: a dropped connection stopped setup (GitHub issue 15).
	git init -q "$SDK"
	git -C "$SDK" remote add origin https://github.com/ValveSoftware/source-sdk-2013.git
	ok=0
	for try in 1 2 3 4; do
		if git -C "$SDK" fetch --depth 1 origin "$BASE"; then ok=1; break; fi
		echo "Download broke off; trying again ($try of 4)..."
		sleep 5
	done
	if (( ! ok )); then
		rm -rf "$SDK"
		echo "Downloading Valve's SDK failed 4 times. Check your internet connection, then run setup again."
		exit 1
	fi
	git -C "$SDK" checkout -q FETCH_HEAD
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

# The Ghost Town's waves run on the Coal Town map (CoalTownMap.java), someone else's map that
# FortCraft can't ship. Its page blocks automatic downloads, so the player downloads it in their
# browser and setup picks the zip up from Downloads (Alex, 2026-10-22: "include it in setup").
COAL_TOWN="$PROJECT/maps/mvm_coaltown.zip"
COAL_TOWN_PAGE="https://minecraft-inside.ru/maps/162670-mvm_coaltown-tf2-rp-map.html"
find_coal_town() {
	local dir zip
	dir=$(xdg-user-dir DOWNLOAD 2>/dev/null || echo "$HOME/Downloads")
	[[ -d "$dir" ]] || dir="$HOME/Downloads"
	while IFS= read -r zip; do
		if ! command -v unzip >/dev/null 2>&1 || unzip -l "$zip" 2>/dev/null | grep -qE '(^|/| )region/r\.-?[0-9]+\.-?[0-9]+\.mca$'; then echo "$zip"; return 0; fi
	done < <(ls -t "$dir"/*[Cc]oal[Tt]own*.zip 2>/dev/null)
	return 0
}
echo
echo "== The Ghost Town map (for Mann vs. Machine waves)"
if [[ -f "$COAL_TOWN" ]]; then
	echo "Already installed."
else
	download=$(find_coal_town)
	if [[ -z "$download" ]]; then
		read -r -p "The Ghost Town's waves need the free mvm_coaltown map. Open its page to download it? [Y/n] " answer || answer=n
		if [[ -z "$answer" || "${answer,,}" == y* ]]; then
			xdg-open "$COAL_TOWN_PAGE" >/dev/null 2>&1 || true
			echo "Open $COAL_TOWN_PAGE, click its download button and save the zip in your Downloads folder."
			while [[ -z "$download" ]]; do
				read -r -p "Press Enter when the download has finished (or type S to skip) " answer || break
				[[ "${answer,,}" == s* ]] && break
				download=$(find_coal_town)
				[[ -n "$download" ]] || echo "No mvm_coaltown zip with a Minecraft world in it is in Downloads yet."
			done
		fi
	fi
	if [[ -n "$download" ]]; then
		mkdir -p "$(dirname -- "$COAL_TOWN")"
		cp -- "$download" "$COAL_TOWN"
		echo "Installed $download"
	else
		echo "Skipped. The Ghost Town will be built from TF2's own map, without waves."
		echo "To add it later, save the zip as $COAL_TOWN"
	fi
fi

echo
echo "Setup finished. Start FortCraft with ./play.sh (normal world) or tools/play.sh (flat test world)."
