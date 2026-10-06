"""Stand-in for the Minecraft half (the host), for testing the link without Minecraft.

Creates the shared mapping, heartbeats at 60 frames a second, and logs when TF2 connects
and when it goes away.

    python tools/fake_minecraft.py [seconds]
"""

import os
import sys
import time

import fortlink as fl


def main():
    seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 10.0
    m = fl.open_mapping()
    m[0:0x30] = bytes(0x30)
    fl.put_u32(m, fl.OFF_VERSION, fl.VERSION)
    fl.put_u32(m, fl.OFF_HOST_PID, os.getpid())
    fl.put_u32(m, fl.OFF_MAGIC, fl.MAGIC)  # last, so TF2 never sees a half-written header
    print(f"[minecraft] link created pid={os.getpid()}", flush=True)

    frame = 0
    connected = False
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        frame += 1
        fl.put_u64(m, fl.OFF_HOST_FRAME, frame)
        fl.put_u64(m, fl.OFF_HOST_HEARTBEAT, fl.now_ms())

        guest_alive = fl.alive(m, fl.OFF_GUEST_HEARTBEAT)
        if guest_alive and not connected:
            print(f"[minecraft] tf2 connected pid={fl.u32(m, fl.OFF_GUEST_PID)} "
                  f"guestFrame={fl.u64(m, fl.OFF_GUEST_FRAME)}", flush=True)
        elif connected and not guest_alive:
            print(f"[minecraft] tf2 gone lastGuestFrame={fl.u64(m, fl.OFF_GUEST_FRAME)}", flush=True)
        connected = guest_alive
        time.sleep(1 / 60)

    print(f"[minecraft] exit hostFrame={frame} guestFrame={fl.u64(m, fl.OFF_GUEST_FRAME)}", flush=True)


if __name__ == "__main__":
    main()
