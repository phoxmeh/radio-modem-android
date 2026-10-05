#!/usr/bin/env python3
"""Extract rig tables (modes, bandwidths, serial settings, meter tables) from a flrig rig class
(src/rigs/<maker>/<RIG>.cxx, GPLv3) and merge them with a hand-written CAT command spec.

flrig has no data-driven rig database: each radio is a C++ class. The tables are data-like and
can be lifted mechanically; the command logic is code, so it lives in tools/rigs/<RIG>.commands.json
(with comments naming the flrig function each entry was derived from).

  tools/flrig_extract.py FT891.cxx tools/rigs/FT-891.commands.json app/src/main/assets/rigs/FT-891.json
"""
import json, re, sys

def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)

def array_body(src, name):
    m = re.search(r'\b' + re.escape(name) + r'\s*\[[^\]]*\]\s*=\s*\{(.*?)\};', src, re.S)
    if not m: raise KeyError(name)
    return m.group(1)

def strings(src, name):
    return re.findall(r'"([^"]*)"', array_body(src, name))

def ints(src, name):
    return [int(x) for x in re.findall(r'-?\d+', array_body(src, name))]

def chars(src, name):
    return re.findall(r"'(.)'", array_body(src, name))

def main(cxx, commands, out):
    src = strip_comments(open(cxx, encoding='latin-1').read())
    cls = re.search(r'RIG_(\w+)::RIG_\1\s*\(\)', src).group(1)           # FT891
    prefix = cls                                                          # tables are named FT891_*
    name = re.search(r'static const char %sname_\[\]\s*=\s*"([^"]+)"' % prefix, src).group(1)

    mode_names = strings(src, 'v%smodes_' % prefix)
    mode_chr = chars(src, '%s_mode_chr' % prefix)
    mode_type = chars(src, '%s_mode_type' % prefix)
    try:
        def_bw = ints(src, '%s_def_bw' % prefix)
    except KeyError:                                                      # newer rigs: separate narrow/wide defaults
        def_bw = ints(src, 'defBW_wide')

    # Bandwidth tables: vXXX_widths_<T>[] labels paired with XXX_wvals_<T>[] CAT values.
    tables = {}
    for m in re.finditer(r'\bv%s_widths_(\w+)\s*\[' % prefix, src):
        t = m.group(1)
        labels = strings(src, 'v%s_widths_%s' % (prefix, t))
        vname = '%s_wvals_%s' % (prefix, 'AMFM' if t.startswith('AMFM') else t)
        try:
            vals = [int(x) for x in re.findall(r'-?\d+', array_body(src, vname))]  # WVALS_LIMIT is a macro
        except KeyError:
            # Single-width modes (AM/FM) have no table of their own; DATA widths use the PSK values on some rigs.
            alt = '%s_wvals_PSK' % prefix if t == 'DATA' else '%s_wvals_AMFM' % prefix
            vals = [int(x) for x in re.findall(r'-?\d+', array_body(src, alt))]
        tables[t] = {'labels': labels, 'values': vals[:len(labels)]}

    # Mode -> bandwidth table, from bwtable(int n): "case mX: ... return FT891_widths_T;"
    enum = re.search(r'enum m\w+\s*\{(.*?)\}', src, re.S).group(1)
    enum_names = [e.strip() for e in enum.split(',') if e.strip()]
    fn = re.search(r'RIG_%s::bwtable\(int n\)\s*\{(.*?)\n\}' % prefix, src, re.S).group(1)
    bw_for_mode, pending = {}, []
    for line in fn.split(';'):
        pending += re.findall(r'case\s+(m\w+)\s*:', line)
        r = re.search(r'return\s+%s_widths_(\w+)' % prefix, line)
        if r:
            for c in pending: bw_for_mode[c] = r.group(1)
            pending = []
    default_table = 'SSB'

    modes = []
    for i, n in enumerate(mode_names):
        em = enum_names[i]
        modes.append({'name': n, 'code': mode_chr[i], 'sideband': mode_type[i],
                      'bwTable': bw_for_mode.get(em, default_table), 'defaultBw': def_bw[i]})

    def meter(nm):
        try:
            return [[float(a), float(b)] for a, b in
                    re.findall(r'\{\s*(\d+)\s*,\s*([\d.]+)\s*\}', array_body(src, nm))]
        except KeyError:
            return []

    baud = int(re.search(r'serial_baudrate\s*=\s*BR(\d+)', src).group(1))
    ser = {'baud': baud}
    for key, field in [('stopBits', 'stopbits'), ('retries', 'serial_retries'),
                       ('timeoutMs', 'serial_timeout'), ('writeDelayMs', 'serial_write_delay'),
                       ('postWriteDelayMs', 'serial_post_write_delay')]:
        m = re.search(r'\b%s\s*=\s*(\d+)\s*;' % field, src)
        if m: ser[key] = int(m.group(1))
    m = re.search(r'serial_rtscts\s*=\s*(true|false)', src)
    if m: ser['rtscts'] = m.group(1) == 'true'

    m = re.search(r'def_freq\s*=\s*(\d+)', src)
    profile = {
        'id': name, 'name': name,
        'source': 'flrig %s (GPLv3)' % cxx.split('/')[-1],
        'serial': ser,
        'defaultFreq': int(m.group(1)) if m else 14070000,
        'modes': modes,
        'bandwidths': tables,
        'powerMeterTable': meter('pwrtbl'),
    }
    profile.update(json.load(open(commands)))
    json.dump(profile, open(out, 'w'), indent=1)
    print('wrote', out, '-', len(modes), 'modes,', {k: len(v['labels']) for k, v in tables.items()})

if __name__ == '__main__':
    main(*sys.argv[1:4])
