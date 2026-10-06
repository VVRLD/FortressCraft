"""Compare what fake TF2 sent (logs/tf2_sent.log) with what Minecraft received and drew
(logs/mc_received.log), matched by sample number.

    python tools/compare_positions.py
"""

import os
import re
import sys

LOGS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "logs")
VEC = r"\(([-\d.]+),([-\d.]+),([-\d.]+)\)"


def main():
    sent = {}
    with open(os.path.join(LOGS, "tf2_sent.log")) as f:
        for line in f:
            m = re.search(r"sample=(\d+) .*sent=" + VEC, line)
            if m:
                sent[int(m.group(1))] = tuple(float(v) for v in m.groups()[1:])

    rows = []
    with open(os.path.join(LOGS, "mc_received.log")) as f:
        for line in f:
            m = re.search(r"frame=(\d+) sample=(\d+) received=" + VEC + " drawn=" + VEC, line)
            if m:
                g = [float(v) for v in m.groups()]
                rows.append((int(g[0]), int(g[1]), tuple(g[2:5]), tuple(g[5:8])))

    if not sent or not rows:
        print(f"FAIL  no data: {len(sent)} sent, {len(rows)} received")
        sys.exit(1)

    def dist(a, b):
        return max(abs(p - q) for p, q in zip(a, b))

    matched = [r for r in rows if r[1] in sent]
    recv_err = max(dist(sent[r[1]], r[2]) for r in matched)
    drawn_err = max(dist(r[2], r[3]) for r in matched)
    seen = len({r[1] for r in rows})

    print(f"sent {len(sent)} positions; Minecraft drew {len(rows)} frames using {seen} different ones")
    print(f"every received sample found in sent log: {len(matched) == len(rows)}")
    print(f"largest difference sent vs received: {recv_err:.6f} blocks")
    print(f"largest difference received vs drawn: {drawn_err:.6f} blocks")
    print("\nside by side (every 100th Minecraft frame):")
    for r in matched[::100]:
        s = sent[r[1]]
        print(f"  sample={r[1]:5d}  sent=({s[0]:.4f},{s[1]:.4f},{s[2]:.4f})  "
              f"received=({r[2][0]:.4f},{r[2][1]:.4f},{r[2][2]:.4f})  "
              f"drawn=({r[3][0]:.4f},{r[3][1]:.4f},{r[3][2]:.4f})")
    ok = len(matched) == len(rows) and recv_err < 1e-3 and drawn_err < 1e-3
    print("\nPASS" if ok else "\nFAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
