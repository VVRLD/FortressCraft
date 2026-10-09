#!/usr/bin/env bash
# Linux version of reset_end.ps1: repeatable, backup-first reset of FortCraft Normal's End.
# Only the FortCraft Normal test world's End is in scope. Never touches the overworld, Nether,
# player data, or any backup. The old End is moved into backups/end-resets/<time>/, not deleted.
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
PROJECT=$(cd -- "$HERE/.." && pwd)
SAVE="$PROJECT/fabric/run/saves/FortCraft Normal"
END="$SAVE/dimensions/minecraft/the_end"
BACKUPS="$PROJECT/backups/end-resets"

[[ -f "$SAVE/level.dat" ]] || { echo "FortCraft Normal save not found: $SAVE"; exit 1; }
[[ -d "$END" ]] || { echo "No existing End folder found at $END. Nothing was changed."; exit 1; }
if [[ -L "$SAVE" || -L "$END" ]]; then
	echo "Refusing to reset through a linked save or End folder."
	exit 1
fi

# A live world may still be writing region files or dragon-fight state.
if pgrep -f 'mod_tf_linux64|hl2_linux' >/dev/null 2>&1 \
	|| pgrep -af java 2>/dev/null | grep -F -- "$PROJECT" | grep -qE 'runClient|fabric\.dli'; then
	echo "Minecraft or TF2 is still running. Close both games, return to the Overworld, then try again."
	exit 1
fi

echo "This resets ONLY the FortCraft Normal End. Its old terrain, entities, and dragon-fight state will be backed up."
echo "Before continuing, your character must have left the End and be in the Overworld."
read -r -p "Type RESET END to continue: " answer
if [[ "$answer" != "RESET END" ]]; then
	echo "Cancelled. Nothing was changed."
	exit 0
fi

stamp=$(date +%Y-%m-%d-%H%M%S)
dest="$BACKUPS/$stamp"
[[ ! -e "$dest" ]] || { echo "Backup destination already exists. Nothing was changed."; exit 1; }
mkdir -p "$dest"
mv -- "$END" "$dest/the_end"
echo "End reset ready. Old End saved at: $dest/the_end"
echo "Next time you enter the End, Minecraft will generate it again with a fresh dragon fight."
