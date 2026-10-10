"""Write tf2mod/maps/fortcraft_flat.vmf: TF2's playing field for FortCraft.

One huge hollow box, as big as the Source engine allows (coordinates up to +-16384 units, so
about 680 x 680 x 680 Minecraft blocks inside), every face invisible (nodraw), and a Red and a
Blue spawn in the middle. Nothing in it is ever seen; Minecraft's blocks are the real world.
Why: on TF2's small test map, anything outside the map's walls is treated as nowhere (not
drawn, not sent), so the player's own model vanished during taunts once you left the map.

    python tools/make_flat_map.py      then      tools\\build_map.bat
"""

import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "tf2mod", "maps", "fortcraft_flat.vmf")

OUTER = 16320  # outside of the walls
WALL = 64
MATERIAL = "TOOLS/TOOLSNODRAW"

_ids = [0]


def next_id():
    _ids[0] += 1
    return _ids[0]


def side(p1, p2, p3, material=MATERIAL):
    pts = " ".join("(%d %d %d)" % p for p in (p1, p2, p3))
    return (
        '\t\tside\n\t\t{\n'
        f'\t\t\t"id" "{next_id()}"\n'
        f'\t\t\t"plane" "{pts}"\n'
        f'\t\t\t"material" "{material}"\n'
        '\t\t\t"uaxis" "[1 0 0 0] 0.25"\n'
        '\t\t\t"vaxis" "[0 -1 0 0] 0.25"\n'
        '\t\t\t"rotation" "0"\n'
        '\t\t\t"lightmapscale" "16"\n'
        '\t\t\t"smoothing_groups" "0"\n'
        '\t\t}\n'
    )


def box(mn, mx, material=None):
    x0, y0, z0 = mn
    x1, y1, z1 = mx
    m = material or MATERIAL
    sides = [
        side((x0, y1, z1), (x1, y1, z1), (x1, y0, z1), m),  # top
        side((x0, y0, z0), (x1, y0, z0), (x1, y1, z0), m),  # bottom
        side((x0, y1, z1), (x0, y0, z1), (x0, y0, z0), m),  # x min
        side((x1, y1, z0), (x1, y0, z0), (x1, y0, z1), m),  # x max
        side((x1, y1, z1), (x0, y1, z1), (x0, y1, z0), m),  # y max
        side((x1, y0, z0), (x0, y0, z0), (x0, y0, z1), m),  # y min
    ]
    return '\tsolid\n\t{\n' + f'\t\t"id" "{next_id()}"\n' + "".join(sides) + '\t}\n'


def entity(classname, origin, extra):
    lines = [f'\t"id" "{next_id()}"', f'\t"classname" "{classname}"', '\t"origin" "%d %d %d"' % origin]
    lines += [f'\t"{k}" "{v}"' for k, v in extra.items()]
    return "entity\n{\n" + "\n".join(lines) + "\n}\n"


def main():
    o, w = OUTER, WALL
    i = o - w
    walls = [
        box((-o, -o, i), (o, o, o)),    # ceiling
        box((-o, -o, -o), (o, o, -i)),  # floor
        box((-o, -o, -i), (-i, o, i)),  # x min
        box((i, -o, -i), (o, o, i)),    # x max
        box((-i, -o, -i), (i, -i, i)),  # y min
        box((-i, i, -i), (i, o, i)),    # y max
        # TF2 won't load a map with no drawable surface at all ("bad surfedges count: 0"), so
        # one tiny ordinary block sits in a far top corner, where nobody goes (and TF2's map is
        # never drawn anyway).
        box((i - 32, i - 32, i - 32), (i - 16, i - 16, i - 16), "DEV/DEV_MEASUREGENERIC01B"),
    ]
    world = (
        "world\n{\n"
        f'\t"id" "{next_id()}"\n'
        '\t"mapversion" "1"\n'
        '\t"classname" "worldspawn"\n'
        '\t"skyname" "sky_tf2_04"\n'
        + "".join(walls)
        + "}\n"
    )
    ents = (
        # Not at (0, 0, 0): TF2 throws away any spawn point sitting exactly on the world origin
        # ("Check for a bad spawn entity" in CTFPlayer::SelectSpawnSpotByType), which left Red
        # with no valid spawn at all, so the player never spawned (no class, no weapon, no HUD).
        entity("info_player_teamspawn", (-64, 0, 0), {"TeamNum": "2", "StartDisabled": "0", "angles": "0 0 0"})
        + entity("info_player_teamspawn", (64, 0, 0), {"TeamNum": "3", "StartDisabled": "0", "angles": "0 0 0"})
        + entity("light_environment", (0, 0, 512), {"_light": "255 255 255 300", "_ambient": "255 255 255 200", "pitch": "-60", "angles": "-60 0 0"})
    )
    header = 'versioninfo\n{\n\t"editorversion" "400"\n\t"mapversion" "1"\n\t"formatversion" "100"\n\t"prefab" "0"\n}\n'
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", newline="\n") as f:
        f.write(header + world + ents)
    print(f"wrote {os.path.normpath(OUT)}: inside {2 * i} units = {2 * i / 48:.0f} blocks across")


if __name__ == "__main__":
    main()
