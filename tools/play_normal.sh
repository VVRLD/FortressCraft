#!/usr/bin/env bash
# Linux: like play.sh, but Minecraft opens a world with normal terrain, caves and mobs
# ("FortCraft Normal", created the first time) instead of the flat test world.
# To play one of your own worlds: copy its folder into fabric/run/saves, then run
#   FORTCRAFT_WORLD="<folder name>" tools/play.sh
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
FORTCRAFT_WORLD=normal exec "$HERE/play.sh" "$@"
