"""Python mirror of protocol/fortcraft_protocol.h, shared by the test stand-ins.

Keep the constants and offsets here identical to the header.
"""

import ctypes
import mmap
import os
import struct
import sys
import time

MAGIC = 0x46524346  # "FCRF"
VERSION = 56
OFF_WATER_BOXES = 0x56000
MAX_WATER_BOXES = 256
OFF_PACK_USE = 0x58000  # request, kind, result, ok (four uint32 values)
WINDOWS = sys.platform == "win32"
# Windows: a named page-file mapping. Linux: a file in /dev/shm (RAM), as in FortLink.java.
NAME = os.environ.get("FORTCRAFT_LINK", "Local\\FortCraft_v1" if WINDOWS else "/dev/shm/FortCraft_v1")
SIZE = 0x100000 + 2 * 3840 * 2160 * 4
HEARTBEAT_TIMEOUT_MS = 1000

# Header field offsets
OFF_MAGIC = 0x00
OFF_VERSION = 0x04
OFF_HOST_PID = 0x08
OFF_GUEST_PID = 0x0C
OFF_HOST_HEARTBEAT = 0x10
OFF_GUEST_HEARTBEAT = 0x18
OFF_HOST_FRAME = 0x20
OFF_GUEST_FRAME = 0x28

# PlayerState @0x100 (TF2 -> Minecraft, seqlock)
OFF_PLAYER = 0x100
PLAYER_VALID = 1
_PLAYER_FIELDS = struct.Struct("<IQdddff")  # flags, sample, x, y, z, yaw, pitch (after seq)

# InputState @0x140 (Minecraft -> TF2, seqlock)
OFF_INPUT = 0x140
IN_FORWARD, IN_BACK, IN_LEFT, IN_RIGHT, IN_JUMP, IN_CROUCH = 1, 2, 4, 8, 16, 32
IN_ATTACK, IN_RELOAD, IN_ATTACK2 = 64, 128, 256
_INPUT_FIELDS = struct.Struct("<IQff")  # buttons, sample, yaw, pitch (after seq)

if WINDOWS:
    _kernel32 = ctypes.windll.kernel32
    _kernel32.GetTickCount64.restype = ctypes.c_uint64


def now_ms():
    """Heartbeat clock: GetTickCount64 on Windows, CLOCK_MONOTONIC on Linux (as both games use)."""
    if WINDOWS:
        return _kernel32.GetTickCount64()
    return time.clock_gettime_ns(time.CLOCK_MONOTONIC) // 1_000_000


def open_mapping():
    """Create or open the mapping. Python can't tell which, so callers check MAGIC."""
    if WINDOWS:
        return mmap.mmap(-1, SIZE, tagname=NAME)
    fd = os.open(NAME, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        if os.fstat(fd).st_size < SIZE:
            os.ftruncate(fd, SIZE)
        return mmap.mmap(fd, SIZE)
    finally:
        os.close(fd)


def u32(m, off):
    return struct.unpack_from("<I", m, off)[0]


def u64(m, off):
    return struct.unpack_from("<Q", m, off)[0]


def put_u32(m, off, v):
    struct.pack_into("<I", m, off, v)


def put_u64(m, off, v):
    struct.pack_into("<Q", m, off, v)


def write_player(m, sample, x, y, z, yaw, pitch):
    seq = u32(m, OFF_PLAYER)
    put_u32(m, OFF_PLAYER, (seq + 1) | 1)  # odd: writing
    _PLAYER_FIELDS.pack_into(m, OFF_PLAYER + 4, PLAYER_VALID, sample, x, y, z, yaw, pitch)
    put_u32(m, OFF_PLAYER, ((seq + 1) | 1) + 1)  # even: done


# OverlayHeader @0x200 (TF2 -> Minecraft): seq, front, width, height; pixels @0x100000, 2 slots
OFF_OVERLAY = 0x200
OFF_OVERLAY_PIXELS = 0x100000
OVERLAY_SLOT_BYTES = 3840 * 2160 * 4


def write_overlay(m, width, height, rgba):
    """Publish one RGBA overlay frame (len(rgba) == width * height * 4)."""
    back = u32(m, OFF_OVERLAY + 4) ^ 1
    start = OFF_OVERLAY_PIXELS + back * OVERLAY_SLOT_BYTES
    m[start:start + len(rgba)] = rgba
    put_u32(m, OFF_OVERLAY + 8, width)
    put_u32(m, OFF_OVERLAY + 12, height)
    put_u32(m, OFF_OVERLAY + 4, back)
    put_u32(m, OFF_OVERLAY, u32(m, OFF_OVERLAY) + 1)


def read_input(m):
    """(buttons, sample, yaw, pitch), or None if Minecraft was mid-write."""
    before = u32(m, OFF_INPUT)
    if before & 1:
        return None
    fields = _INPUT_FIELDS.unpack_from(m, OFF_INPUT + 4)
    return fields if u32(m, OFF_INPUT) == before else None


def alive(m, heartbeat_off):
    beat = u64(m, heartbeat_off)
    return beat != 0 and now_ms() - beat < HEARTBEAT_TIMEOUT_MS
