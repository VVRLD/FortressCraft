"""Step 1 test: the two stand-ins find each other and exchange heartbeats.

Starts fake_minecraft.py for 6 seconds and fake_tf2.py for 3 seconds, so TF2 connects,
runs, then stops while Minecraft is still running. Writes both outputs to logs/link_test.log
and prints PASS or FAIL for each check.

    python tools/test_link.py
"""

import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "..", "logs", "link_test.log")


def main():
    env = dict(os.environ, FORTCRAFT_LINK=f"Local\\FortCraft_test_{os.getpid()}")
    mc = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_minecraft.py"), "6"],
                          stdout=subprocess.PIPE, text=True, env=env, cwd=HERE)
    time.sleep(0.5)
    tf2 = subprocess.Popen([sys.executable, os.path.join(HERE, "fake_tf2.py"), "3"],
                           stdout=subprocess.PIPE, text=True, env=env, cwd=HERE)
    tf2_out = tf2.communicate(timeout=20)[0]
    mc_out = mc.communicate(timeout=20)[0]

    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    with open(LOG, "w") as f:
        f.write(mc_out + tf2_out)

    def frames(pattern, text):
        found = re.search(pattern, text)
        return int(found.group(1)) if found else 0

    checks = [
        ("tf2 opened the link", "[tf2] link opened" in tf2_out),
        ("minecraft saw tf2 connect", "[minecraft] tf2 connected" in mc_out),
        ("minecraft noticed tf2 stop", "[minecraft] tf2 gone" in mc_out),
        ("tf2 saw minecraft frames advance", frames(r"\[tf2\] exit .* hostFrame=(\d+)", tf2_out) > 60),
        ("minecraft saw tf2 frames advance", frames(r"\[minecraft\] tf2 gone lastGuestFrame=(\d+)", mc_out) > 60),
        ("both exited cleanly", mc.returncode == 0 and tf2.returncode == 0),
    ]
    for name, ok in checks:
        print(f"{'PASS' if ok else 'FAIL'}  {name}")
    print(f"log: {os.path.normpath(LOG)}")
    sys.exit(0 if all(ok for _, ok in checks) else 1)


if __name__ == "__main__":
    main()
