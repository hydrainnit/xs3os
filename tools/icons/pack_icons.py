"""Packs the Figma-exported XMB icon SVGs into assets/xmb_icons.txt.

usage: python3 -I tools/icons/pack_icons.py <svg_dir> <out_file>
One line per icon: name width height d  (all paths of the SVG joined; absolute M L H V C Z only).
"""
import os, re, sys

src, out = sys.argv[1], sys.argv[2]
num = re.compile(r'-?\d*\.?\d+(?:e[-+]?\d+)?', re.I)
lines = []
for f in sorted(os.listdir(src)):
    if not f.endswith('.svg'):
        continue
    t = open(os.path.join(src, f), encoding='utf-8').read()
    m = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', t)
    w, h = float(m.group(1)), float(m.group(2))
    ds = re.findall(r'<path[^>]* d="([^"]+)"', t)
    d = ' '.join(ds)
    d = num.sub(lambda mm: ('%.2f' % float(mm.group(0))).rstrip('0').rstrip('.') if '.' in ('%.2f' % float(mm.group(0))) else '%d' % float(mm.group(0)), d)
    d = re.sub(r'\s+', ' ', d).replace('-0 ', '0 ')
    lines.append('%s\t%g\t%g\t%s' % (f[:-4], w, h, d))
open(out, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
print(len(lines), 'icons', os.path.getsize(out), 'bytes')
