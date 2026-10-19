"""Exit code 0 if FortCraft's Minecraft is still running and TF2 had linked to it, 1 if not.

    python tools/minecraft_running.py

The launch scripts run this after TF2 closes: if Minecraft is still open, TF2 crashed or quit on
its own (for example when the sound device changed, GitHub issue 19), so they start TF2 again
and it reconnects. A TF2 that never linked (it couldn't start at all) isn't restarted.
Reads the link's header only: its magic number and the two process ids.
"""

import ctypes
import os
import sys

import fortlink


def process_alive(pid):
    if pid <= 0:
        return False
    if fortlink.WINDOWS:
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            code = ctypes.c_ulong()
            return bool(kernel32.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == 259  # STILL_ACTIVE
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True
    except OSError:
        return False


def main():
    if not fortlink.WINDOWS and not os.path.exists(fortlink.NAME):
        return 1
    m = fortlink.open_mapping()
    try:
        if fortlink.u32(m, fortlink.OFF_MAGIC) != fortlink.MAGIC:
            return 1
        if fortlink.u32(m, fortlink.OFF_GUEST_PID) == 0:
            return 1
        return 0 if process_alive(fortlink.u32(m, fortlink.OFF_HOST_PID)) else 1
    finally:
        m.close()


if __name__ == "__main__":
    sys.exit(main())
