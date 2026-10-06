"""Stand-in for the TF2 half (the hidden guest), for testing without TF2.

Waits for Minecraft's link, checks magic and version, then runs like TF2 does:
- the player's "real" position only changes on game ticks, 66 times a second, walking a
  circle of radius 4 blocks around (0.5, -60, 0.5) (the surface of a default flat world);
- each render frame (about 144 a second) it sends the position interpolated between the
  last two ticks, which is what TF2 itself draws, not the raw tick position.

Every value sent is written to logs/tf2_sent.log so it can be compared with what
Minecraft received (tools/compare_positions.py).

    python tools/fake_tf2.py [seconds]
"""

import math
import os
import sys
import time

import fortlink as fl

TICK = 1 / 66       # TF2 server tick
FRAME = 1 / 144     # render frame
CENTER = (0.5, -60.0, 0.5)
RADIUS = 4.0
SPEED = 4.3         # blocks a second, a little faster than Minecraft walking
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "logs", "tf2_sent.log")


def tick_position(n):
    """Feet position and facing after tick n."""
    angle = n * TICK * SPEED / RADIUS
    x = CENTER[0] + RADIUS * math.cos(angle)
    z = CENTER[2] + RADIUS * math.sin(angle)
    # Facing along the circle. Minecraft yaw: 0 faces +Z, 90 faces -X.
    dx, dz = -math.sin(angle), math.cos(angle)
    yaw = math.degrees(math.atan2(-dx, dz))
    return x, CENTER[1], z, yaw


def lerp_angle(a, b, t):
    d = (b - a + 180) % 360 - 180
    return a + d * t


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 10.0
    end = time.monotonic() + seconds
    m = fl.open_mapping()

    while fl.u32(m, fl.OFF_MAGIC) != fl.MAGIC:
        if time.monotonic() > end:
            print("[tf2] no link from minecraft, giving up", flush=True)
            sys.exit(1)
        time.sleep(0.05)

    version = fl.u32(m, fl.OFF_VERSION)
    if version != fl.VERSION:
        print(f"[tf2] version mismatch: link={version} ours={fl.VERSION}", flush=True)
        sys.exit(1)

    fl.put_u32(m, fl.OFF_GUEST_PID, os.getpid())
    print(f"[tf2] link opened pid={os.getpid()} hostPid={fl.u32(m, fl.OFF_HOST_PID)}", flush=True)

    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    log = open(LOG, "w")

    start = time.monotonic()
    frame = 0
    host_was_alive = True
    while time.monotonic() < end:
        frame += 1
        now = time.monotonic() - start
        tick = int(now / TICK)
        alpha = now / TICK - tick  # how far we are between tick and tick + 1
        x0, y0, z0, yaw0 = tick_position(tick)
        x1, y1, z1, yaw1 = tick_position(tick + 1)
        x = x0 + (x1 - x0) * alpha
        y = y0 + (y1 - y0) * alpha
        z = z0 + (z1 - z0) * alpha
        yaw = lerp_angle(yaw0, yaw1, alpha)

        fl.write_player(m, frame, x, y, z, yaw, 0.0)
        log.write(f"sample={frame} tick={tick} alpha={alpha:.3f} sent=({x:.4f},{y:.4f},{z:.4f}) yaw={yaw:.2f}\n")
        fl.put_u64(m, fl.OFF_GUEST_FRAME, frame)
        fl.put_u64(m, fl.OFF_GUEST_HEARTBEAT, fl.now_ms())

        host_alive = fl.alive(m, fl.OFF_HOST_HEARTBEAT)
        if host_was_alive and not host_alive:
            print(f"[tf2] minecraft gone lastHostFrame={fl.u64(m, fl.OFF_HOST_FRAME)}", flush=True)
        host_was_alive = host_alive
        time.sleep(FRAME)

    log.close()
    print(f"[tf2] exit guestFrame={frame} hostFrame={fl.u64(m, fl.OFF_HOST_FRAME)}", flush=True)


if __name__ == "__main__":
    main()
