"""Draws FortCraft's own white Ghost Town portal animation (32 frames of soft swirls, 16x16 each)
into fabric/src/main/resources/assets/fortcraft/textures/block/mvm_portal.png. Original art, made
from maths, not copied from Minecraft. Run: python tools/make_portal_texture.py"""
import math
import os
import struct
import zlib

FRAMES = 32
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'fabric', 'src', 'main', 'resources',
                   'assets', 'fortcraft', 'textures', 'block', 'mvm_portal.png')


def pixel(x, y, f):
    t = f * 2 * math.pi / FRAMES
    cx, cy = x - 7.5, y - 7.5
    r, ang = math.hypot(cx, cy), math.atan2(cy, cx)
    v = 0.5 + 0.25 * math.sin(ang * 2 + r * 0.9 - t) + 0.25 * math.sin(x * 0.8 + t) * math.cos(y * 0.7 - t)
    v = max(0.0, min(1.0, v))
    c = int(205 + 50 * v)
    return bytes((c, c, min(255, c + 8), int(150 + 80 * v)))


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)


rows = b''.join(b'\0' + b''.join(pixel(x, y % 16, y // 16) for x in range(16)) for y in range(16 * FRAMES))
png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16 * FRAMES, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b''))
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, 'wb') as f:
    f.write(png)
print('wrote', os.path.normpath(OUT))
