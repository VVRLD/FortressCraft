"""Copies the player's own TF2 graphics settings into FortCraft's TF2.

    python tools/tf2_graphics.py <output .cfg>

Run by tools/run_tf2.bat and tools/run_tf2.sh on every start; TF2 runs the file with +exec.
Reads (never writes) the real Team Fortress 2's settings:
  - Windows: TF2's video options in the registry (HKCU\\Software\\Valve\\Source\\tf\\Settings)
  - Linux: the same keys in Steam's ~/.steam/registry.vdf
  - both: tf/cfg/config.cfg in the Steam install (used where the registry has no value)
Only picture-quality settings are copied (texture, model, shader and shadow detail, anti-aliasing,
filtering). Settings FortCraft needs a certain way (TF2's map not drawn, no HDR, no specular
shine, no colour correction) stay as fortcraft_link.cpp sets them. Motion blur and vertical sync
are always off. With nothing found, the file only turns those two off and TF2 keeps its own.
"""
import os
import re
import sys

# Cvar name -> where it may come from. Registry names match the cvars.
QUALITY = [
    'mat_picmip',               # texture detail
    'r_rootlod',                # model detail
    'mat_reducefillrate',       # shader detail
    'r_shadowrendertotexture',  # shadow detail
    'mat_antialias', 'mat_aaquality',
    'mat_forceaniso', 'mat_trilinear',
    'mat_bumpmap', 'mat_parallaxmap',
    'r_lod',
]
ALWAYS = [('mat_vsync', '0'), ('mat_motion_blur_enabled', '0')]


def steam_roots():
    roots = []
    if os.name == 'nt':
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Valve\Steam') as k:
                roots.append(winreg.QueryValueEx(k, 'SteamPath')[0])
        except OSError:
            pass
        roots += [r'C:\Program Files (x86)\Steam', r'C:\Program Files\Steam']
    else:
        home = os.path.expanduser('~')
        roots += [os.path.join(home, '.steam', 'steam'), os.path.join(home, '.local', 'share', 'Steam'),
                  os.path.join(home, '.var', 'app', 'com.valvesoftware.Steam', '.local', 'share', 'Steam')]
    return [r for r in roots if os.path.isdir(r)]


def tf_dir():
    libraries = []
    for root in steam_roots():
        libraries.append(root)
        vdf = os.path.join(root, 'steamapps', 'libraryfolders.vdf')
        try:
            text = open(vdf, encoding='utf-8', errors='replace').read()
            libraries += [p.replace('\\\\', '\\') for p in re.findall(r'"path"\s+"([^"]+)"', text)]
        except OSError:
            pass
    for lib in libraries:
        tf = os.path.join(lib, 'steamapps', 'common', 'Team Fortress 2', 'tf')
        if os.path.isdir(tf):
            return tf
    return None


def from_config_cfg(tf, out):
    try:
        text = open(os.path.join(tf, 'cfg', 'config.cfg'), encoding='utf-8', errors='replace').read()
    except OSError:
        return 0
    n = 0
    for name, value in re.findall(r'^\s*(\w+)\s+"?([^"\r\n]*)"?', text, re.M):
        if name in QUALITY:
            out[name] = value.strip()
            n += 1
    return n


def signed(v):
    v = int(v)
    return v - (1 << 32) if v >= 1 << 31 else v


def from_registry(out):
    """TF2's video options: the real registry on Windows, Steam's registry.vdf on Linux."""
    values = {}
    if os.name == 'nt':
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Valve\Source\tf\Settings') as k:
                i = 0
                while True:
                    try:
                        name, value, _ = winreg.EnumValue(k, i)
                    except OSError:
                        break
                    values[name.lower()] = value
                    i += 1
        except OSError:
            return 0
    else:
        path = os.path.expanduser('~/.steam/registry.vdf')
        try:
            tokens = re.findall(r'"((?:[^"\\]|\\.)*)"|([{}])', open(path, encoding='utf-8', errors='replace').read())
        except OSError:
            return 0
        want = ['registry', 'hkcu', 'software', 'valve', 'source', 'tf', 'settings']
        stack, key = [], None
        for text, brace in tokens:
            if brace == '{':
                stack.append((key or '').lower())
                key = None
            elif brace == '}':
                if stack:
                    stack.pop()
                key = None
            elif key is None:
                key = text
            else:
                if stack == want:
                    values[key.lower()] = text
                key = None
    n = 0
    for name in QUALITY:
        if name.lower() in values:
            try:
                out[name] = str(signed(values[name.lower()]))
            except (TypeError, ValueError):
                continue
            n += 1
    return n


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    settings = {}
    tf = tf_dir()
    from_cfg = from_config_cfg(tf, settings) if tf else 0
    from_reg = from_registry(settings)
    lines = ['// Written by FortCraft\'s tools/tf2_graphics.py on every start: your own TF2 graphics',
             '// settings (%d from TF2\'s video options, %d from its config.cfg), plus motion blur and' % (from_reg, from_cfg),
             '// vertical sync always off.']
    lines += ['%s "%s"' % (k, v) for k, v in settings.items()]
    lines += ['%s "%s"' % kv for kv in ALWAYS]
    os.makedirs(os.path.dirname(os.path.abspath(sys.argv[1])), exist_ok=True)
    with open(sys.argv[1], 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('FortCraft: TF2 graphics from your TF2: %s' % (', '.join('%s %s' % kv for kv in settings.items()) or 'none found'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
