"""Test the Minecraft side of the TF2 overlay without TF2.

Pretends to be TF2 for a few seconds: heartbeats and sends a 1280x720 test overlay that is
transparent except for a white frame around the edge, a green cross in the middle and a red
box bottom-left (where TF2's health sits). Minecraft should log "TF2 overlay 1280x720" and draw
it in place of its own HUD and hand.

    python tools/fake_overlay.py [seconds]
"""

import os
import sys
import time

import fortlink as fl

W, H = 1280, 720


def pattern():
    px = bytearray(W * H * 4)  # all transparent

    def fill(x0, y0, x1, y1, r, g, b):
        for y in range(y0, y1):
            row = (y * W) * 4
            px[row + x0 * 4:row + x1 * 4] = bytes((r, g, b, 255)) * (x1 - x0)

    fill(0, 0, W, 4, 255, 255, 255)
    fill(0, H - 4, W, H, 255, 255, 255)
    fill(0, 0, 4, H, 255, 255, 255)
    fill(W - 4, 0, W, H, 255, 255, 255)
    fill(W // 2 - 20, H // 2 - 2, W // 2 + 20, H // 2 + 2, 0, 255, 0)
    fill(W // 2 - 2, H // 2 - 20, W // 2 + 2, H // 2 + 20, 0, 255, 0)
    fill(40, H - 120, 240, H - 40, 220, 40, 40)
    return bytes(px)


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 15.0
    m = fl.open_mapping()
    while fl.u32(m, fl.OFF_MAGIC) != fl.MAGIC:
        time.sleep(0.2)
    if fl.u32(m, fl.OFF_VERSION) != fl.VERSION:
        print(f"[overlay] version mismatch: link={fl.u32(m, fl.OFF_VERSION)} ours={fl.VERSION}", flush=True)
        sys.exit(1)
    fl.put_u32(m, fl.OFF_GUEST_PID, os.getpid())
    img = pattern()
    end = time.monotonic() + seconds
    frame = 0
    while time.monotonic() < end:
        frame += 1
        fl.put_u64(m, fl.OFF_GUEST_FRAME, frame)
        fl.put_u64(m, fl.OFF_GUEST_HEARTBEAT, fl.now_ms())
        if frame % 4 == 1:
            fl.write_overlay(m, W, H, img)
        time.sleep(1 / 60)
    print(f"[overlay] sent {frame} frames", flush=True)


if __name__ == "__main__":
    main()
