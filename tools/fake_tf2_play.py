"""Playable stand-in for the TF2 half, until the real TF2 mod exists.

Reads the keys Minecraft forwards (W/A/S/D, jump, crouch) and Minecraft's look direction,
and moves the player with TF2-style movement on a flat floor at y = -60 (the surface of a
default flat world): Source-engine ground friction and acceleration, gravity, jumping, and
air strafing. The player's position changes on 66-per-second ticks; each ~144-per-second
render frame the position interpolated between the last two ticks is sent to Minecraft.

Runs until Minecraft closes. Every value sent goes to logs/tf2_sent.log, and once a second a
summary line goes to logs/tf2_play.log.

    python tools/fake_tf2_play.py
"""

import math
import os
import sys
import time

import fortlink as fl

TICK = 1 / 66
FRAME = 1 / 144
UNITS = 48.0                 # TF2 units per Minecraft block (proposed scale)
FLOOR_Y = -60.0
START = (0.5, FLOOR_Y, 0.5)

# TF2 movement settings (TF2's defaults; speeds in TF2 units)
MAX_SPEED = 240 / UNITS      # Soldier run speed
CROUCH_SPEED = MAX_SPEED / 3
GRAVITY = 800 / UNITS        # sv_gravity
JUMP_SPEED = 289 / UNITS     # TF2 jump
FRICTION = 4.0               # sv_friction
STOP_SPEED = 100 / UNITS     # sv_stopspeed
ACCELERATE = 10.0            # sv_accelerate
AIR_ACCELERATE = 10.0        # sv_airaccelerate
AIR_WISH_CAP = 30 / UNITS    # Source caps air wish speed: this is what makes air strafing work

HERE = os.path.dirname(os.path.abspath(__file__))
LOGS = os.path.join(HERE, "..", "logs")


def accelerate(vx, vz, wx, wz, wish_speed, accel, dt):
    """Source's PM_Accelerate on the horizontal plane. (wx, wz) is a unit vector."""
    current = vx * wx + vz * wz
    add = wish_speed - current
    if add <= 0:
        return vx, vz
    gain = min(accel * dt * wish_speed, add)
    return vx + gain * wx, vz + gain * wz


class Player:
    def __init__(self):
        self.x, self.y, self.z = START
        self.vx = self.vy = self.vz = 0.0
        self.on_ground = True
        self.jump_held = False

    def tick(self, buttons, yaw):
        dt = TICK
        # Wish direction from keys, relative to where Minecraft is looking.
        # Minecraft yaw 0 faces +Z; forward = (-sin, cos), left = (cos, sin).
        r = math.radians(yaw)
        fx, fz = -math.sin(r), math.cos(r)
        lx, lz = math.cos(r), math.sin(r)
        fwd = (1 if buttons & fl.IN_FORWARD else 0) - (1 if buttons & fl.IN_BACK else 0)
        side = (1 if buttons & fl.IN_LEFT else 0) - (1 if buttons & fl.IN_RIGHT else 0)
        wx, wz = fwd * fx + side * lx, fwd * fz + side * lz
        length = math.hypot(wx, wz)
        crouch = bool(buttons & fl.IN_CROUCH)
        wish_speed = (CROUCH_SPEED if crouch and self.on_ground else MAX_SPEED) if length else 0.0
        if length:
            wx, wz = wx / length, wz / length

        jump = bool(buttons & fl.IN_JUMP)
        if self.on_ground and jump and not self.jump_held:
            self.vy = JUMP_SPEED
            self.on_ground = False
        self.jump_held = jump  # TF2 needs a fresh press for each jump

        if self.on_ground:
            speed = math.hypot(self.vx, self.vz)
            if speed > 0:
                drop = max(speed, STOP_SPEED) * FRICTION * dt
                scale = max(speed - drop, 0) / speed
                self.vx *= scale
                self.vz *= scale
            self.vx, self.vz = accelerate(self.vx, self.vz, wx, wz, wish_speed, ACCELERATE, dt)
        else:
            self.vx, self.vz = accelerate(self.vx, self.vz, wx, wz, min(wish_speed, AIR_WISH_CAP), AIR_ACCELERATE, dt)
            self.vy -= GRAVITY * dt

        self.x += self.vx * dt
        self.z += self.vz * dt
        self.y += self.vy * dt
        if self.y <= FLOOR_Y:
            self.y = FLOOR_Y
            self.vy = 0.0
            self.on_ground = True


def main():
    m = fl.open_mapping()
    print("[tf2] waiting for minecraft...", flush=True)
    while fl.u32(m, fl.OFF_MAGIC) != fl.MAGIC or not fl.alive(m, fl.OFF_HOST_HEARTBEAT):
        time.sleep(0.2)
    version = fl.u32(m, fl.OFF_VERSION)
    if version != fl.VERSION:
        print(f"[tf2] version mismatch: link={version} ours={fl.VERSION}", flush=True)
        sys.exit(1)
    fl.put_u32(m, fl.OFF_GUEST_PID, os.getpid())
    print(f"[tf2] link opened pid={os.getpid()} hostPid={fl.u32(m, fl.OFF_HOST_PID)}", flush=True)

    os.makedirs(LOGS, exist_ok=True)
    sent_log = open(os.path.join(LOGS, "tf2_sent.log"), "w")
    play_log = open(os.path.join(LOGS, "tf2_play.log"), "w", buffering=1)

    player = Player()
    prev = (player.x, player.y, player.z)
    buttons, yaw = 0, 0.0
    start = time.monotonic()
    ticks_done = 0
    frame = 0
    last_summary = 0
    host_dead_since = None

    while True:
        now = time.monotonic() - start
        # Run every tick that is due, with the input as it is now.
        got = fl.read_input(m)
        if got:
            buttons, _, yaw, _ = got
        while (ticks_done + 1) * TICK <= now:
            prev = (player.x, player.y, player.z)
            player.tick(buttons, yaw)
            ticks_done += 1

        # Render frame: position between the last two ticks.
        alpha = min((now - ticks_done * TICK) / TICK, 1.0)
        cur = (player.x, player.y, player.z)
        x, y, z = (p + (c - p) * alpha for p, c in zip(prev, cur))
        frame += 1
        fl.write_player(m, frame, x, y, z, yaw, 0.0)
        sent_log.write(f"sample={frame} tick={ticks_done} alpha={alpha:.3f} sent=({x:.4f},{y:.4f},{z:.4f}) yaw={yaw:.2f}\n")
        fl.put_u64(m, fl.OFF_GUEST_FRAME, frame)
        fl.put_u64(m, fl.OFF_GUEST_HEARTBEAT, fl.now_ms())

        if int(now) != last_summary:
            last_summary = int(now)
            play_log.write(f"t={now:.0f}s pos=({x:.2f},{y:.2f},{z:.2f}) speed={math.hypot(player.vx, player.vz) * UNITS:.0f}u/s "
                           f"onGround={player.on_ground} buttons={buttons:06b} yaw={yaw:.1f}\n")

        if fl.alive(m, fl.OFF_HOST_HEARTBEAT):
            host_dead_since = None
        elif host_dead_since is None:
            host_dead_since = now
        elif now - host_dead_since > 3:
            print("[tf2] minecraft closed, stopping", flush=True)
            break
        time.sleep(FRAME)

    sent_log.close()
    play_log.close()


if __name__ == "__main__":
    main()
