#!/usr/bin/env bash
# Start FortCraft on Linux (normal terrain with mobs). For the flat test world: tools/play.sh
exec bash "$(dirname -- "${BASH_SOURCE[0]}")/tools/play_normal.sh" "$@"
