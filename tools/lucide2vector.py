"""Convert Lucide SVG icons (24x24, stroke-only) into Android VectorDrawable XML.
Usage: python3 -I lucide2vector.py <svg-dir> <res/drawable dir> name...
Output files are named lucide_<name>.xml (dashes -> underscores). White strokes; tint in code."""
import sys, os, xml.etree.ElementTree as ET

def f(v): return float(v or 0)

def to_path(el):
    t = el.tag.split('}')[-1]; a = el.attrib
    if t == 'path': return a['d']
    if t == 'circle':
        cx, cy, r = f(a['cx']), f(a['cy']), f(a['r'])
        return f"M{cx-r},{cy}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0"
    if t == 'line':
        return f"M{a['x1']},{a['y1']}L{a['x2']},{a['y2']}"
    if t in ('polyline', 'polygon'):
        pts = a['points'].replace(',', ' ').split()
        xy = [f"{pts[i]},{pts[i+1]}" for i in range(0, len(pts), 2)]
        return "M" + "L".join(xy) + ("z" if t == 'polygon' else "")
    if t == 'rect':
        x, y, w, h = f(a.get('x')), f(a.get('y')), f(a['width']), f(a['height'])
        r = f(a.get('rx', a.get('ry', 0)))
        if not r: return f"M{x},{y}h{w}v{h}h{-w}z"
        return (f"M{x+r},{y}h{w-2*r}a{r},{r} 0 0 1 {r},{r}v{h-2*r}a{r},{r} 0 0 1 {-r},{r}"
                f"h{-(w-2*r)}a{r},{r} 0 0 1 {-r},{-r}v{-(h-2*r)}a{r},{r} 0 0 1 {r},{-r}z")
    raise SystemExit(f"unsupported element {t}")

src, out = sys.argv[1], sys.argv[2]
for name in sys.argv[3:]:
    root = ET.parse(os.path.join(src, name + '.svg')).getroot()
    paths = [to_path(e) for e in root.iter() if e is not root and e.tag.split('}')[-1] != 'title']
    body = "\n".join(
        f'    <path android:pathData="{d}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2"\n'
        f'        android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />'
        for d in paths)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Lucide "%s" (lucide-static 0.460.0, ISC licence) -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
           '%s\n</vector>\n') % (name, body)
    open(os.path.join(out, 'lucide_' + name.replace('-', '_') + '.xml'), 'w').write(xml)
    print('ok', name, len(paths), 'paths')
