"""Movement-jitter report from the last game run. Run after closing the games:

    python tools/analyze_jitter.py

Reads logs/mc_received.log (every Minecraft frame: TF2 position, time, whether a new TF2 frame
arrived), Minecraft's log (5-second "jitter" lines) and TF2's console.log ("FortCraft jitter"
lines). Prints numbers, not a verdict:
  - stalls: moving frames that showed no new TF2 position (the picture stops for a frame)
  - skips: frames whose step was about twice the usual (a TF2 frame was never shown)
  - one-frame reversals: the player moved backwards for a single frame (a twitch)
  - frame-time spread: how even Minecraft's frames were
  - TF2 prediction errors: TF2's client and server disagreeing about where you are
"""

import glob
import math
import os
import re
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.normpath(os.path.join(HERE, '..'))
ROW = re.compile(r'frame=(\d+) sample=(\d+) received=\(([-\d.]+),([-\d.]+),([-\d.]+)\).*?(?: t=(\d+) newOverlay=(\d))?(?: cam=\(([-\d.]+),([-\d.]+),([-\d.]+)\))?(?: camT=(\d+))?$')


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))] if values else 0.0


def analyze_positions(path):
    rows = []
    for line in open(path, errors='replace'):
        m = ROW.match(line.strip())
        if m:
            # The camera position and the time it was placed when logged (2026-10-09 on: camT; the
            # line holds the previous frame's camera), else TF2's latest player position.
            x, y, z = (m.group(8), m.group(9), m.group(10)) if m.group(8) else (m.group(3), m.group(4), m.group(5))
            t = m.group(11) if m.group(11) and m.group(11) != '0' else m.group(6)
            rows.append((int(m.group(2)), float(x), float(y), float(z),
                         int(t) if t else None, int(m.group(7)) if m.group(7) else None))
    print('Minecraft frames logged: %d (%s)' % (len(rows), path))
    if len(rows) < 3:
        return
    moving = stalls = skips = reversals = 0
    steps, frame_ms = [], []
    for i in range(2, len(rows)):
        s0, *a = rows[i - 2]
        s1, *b = rows[i - 1]
        s2, *c = rows[i]
        d1 = (b[0] - a[0], b[1] - a[1], b[2] - a[2])
        d2 = (c[0] - b[0], c[1] - b[1], c[2] - b[2])
        v1 = math.sqrt(sum(x * x for x in d1))
        v2 = math.sqrt(sum(x * x for x in d2))
        if b[3] is not None and c[3] is not None:
            frame_ms.append((c[3] - b[3]) / 1000.0)
        if v1 < 0.002 or v1 > 1.0:  # standing still, or a teleport/respawn
            continue
        moving += 1
        steps.append(v2)
        if s2 == s1 or v2 < 0.0005:
            stalls += 1
        elif v2 > 1.7 * v1 and v1 > 0.005:
            skips += 1
        if v2 > 0.0005 and sum(d1[k] * d2[k] for k in range(3)) / (v1 * v2) < -0.5:
            reversals += 1
    if moving:
        print('  moving frames: %d' % moving)
        print('  stalls (no new position): %d (%.1f%%)' % (stalls, 100.0 * stalls / moving))
        print('  skips (about a double step): %d (%.1f%%)' % (skips, 100.0 * skips / moving))
        print('  one-frame reversals (twitches): %d' % reversals)
        print('  step per frame, blocks: median %.4f, 5%% %.4f, 95%% %.4f' % (pct(steps, 0.5), pct(steps, 0.05), pct(steps, 0.95)))
    # Speed wobble: with evenly spaced frames, the speed shown changes little from one frame to
    # the next while moving steadily. 1.00 = perfectly even; 0.86 / 1.25 was round 1's 5%/95%.
    ratios = []
    for i in range(2, len(rows) - 1):
        a, b, c = rows[i - 1], rows[i], rows[i + 1]
        if None in (a[4], b[4], c[4]):
            continue
        dt1, dt2 = (b[4] - a[4]) / 1e6, (c[4] - b[4]) / 1e6
        if not (0 < dt1 < 0.02 and 0 < dt2 < 0.02):
            continue
        v1 = math.hypot(b[1] - a[1], b[3] - a[3]) / dt1
        v2 = math.hypot(c[1] - b[1], c[3] - b[3]) / dt2
        if v1 > 2 and v2 > 2:
            ratios.append(v2 / v1)
    if ratios:
        print('  speed wobble frame to frame (1.00 = smooth): 5%% %.2f, median %.2f, 95%% %.2f; frames off by >25%%: %d of %d'
              % (pct(ratios, 0.05), pct(ratios, 0.5), pct(ratios, 0.95), len([x for x in ratios if x > 1.25 or x < 0.8]), len(ratios)))
    if frame_ms:
        good = [f for f in frame_ms if 0 < f < 100]
        if good:
            print('  Minecraft frame time, ms: median %.2f, 5%% %.2f, 95%% %.2f, spread (stdev) %.2f'
                  % (pct(good, 0.5), pct(good, 0.05), pct(good, 0.95), statistics.pstdev(good)))


def grep(paths, needle, limit):
    found = []
    for path in paths:
        if os.path.isfile(path):
            for line in open(path, errors='replace'):
                if needle in line:
                    found.append(line.rstrip())
    for line in found[-limit:]:
        print('  ' + line[-220:])
    return found


def main():
    analyze_positions(os.path.join(PROJECT, 'logs', 'mc_received.log'))
    print('\nMinecraft 5-second jitter lines (pacing, repeats, skipped TF2 frames):')
    grep([os.path.join(PROJECT, 'fabric', 'run', 'logs', 'latest.log')], 'FortCraft: jitter', 8)
    consoles = glob.glob(os.path.join(PROJECT, '..', 'source-sdk-2013', 'game', 'mod_tf', 'console.log'))
    consoles.append(os.path.join(PROJECT, 'logs', 'tf2_perf.log'))  # 2026-10-09 on
    print('\nTF2 prediction errors (client and server disagreeing):')
    totals = grep(consoles, 'prediction errors in', 6)
    grep(consoles, 'FortCraft jitter: prediction error', 10)
    if not totals:
        print('  (no TF2 jitter lines: TF2 not run with this build yet, or console.log not found)')


if __name__ == '__main__':
    main()
