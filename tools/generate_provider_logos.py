#!/usr/bin/env python3
"""Render pinned identity SVGs to offline Android resources (host: Inkscape + Pillow)."""
import argparse, base64, hashlib, io, json, re, subprocess, tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'third_party/models-dev-logos'
OUT = ROOT / 'app/src/main/res/drawable-nodpi'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--only', help='Regenerate a single upstream provider ID')
    args = parser.parse_args()
    manifest = json.loads((ASSETS / 'manifest.json').read_text())
    if args.check:
        assert len({entry['id'] for entry in manifest['assets']}) == len(manifest['assets']), 'Duplicate provider IDs'
        resources = {entry['resource'] for entry in manifest['assets']}
        assert len(resources) == len(manifest['assets']), 'Colliding Android resource names'
        assert {p.stem for p in OUT.glob('provider_catalog_*.png')} == resources, 'Missing or obsolete logo resources'
        for entry in manifest['assets']:
            assert digest(ASSETS / 'svg' / (entry['id']+'.svg')) == entry['svg_sha256'], entry['id']
            png = OUT / (entry['resource']+'.png')
            assert digest(png) == entry['png_sha256'], entry['id']
            with Image.open(png) as pixels:
                pixels.verify()
            with Image.open(png) as pixels:
                assert pixels.mode == 'RGBA' and max(pixels.size) <= 192 and pixels.getbbox(), entry['id']
        binding = (ROOT/'app/src/main/java/com/baystudio/droide/ui/ProviderCatalogBrand.kt').read_text()
        actual = dict(re.findall(r'"([^"]+)" -> R\.drawable\.([a-z0-9_]+)', binding))
        expected = {entry['id']:entry['resource'] for entry in manifest['assets']}
        for alias, target in manifest['aliases'].items():
            if alias not in expected and target in expected: expected[alias] = expected[target]
        assert actual == expected, 'Kotlin provider ID/resource mapping is stale'
        print(f"PROVIDER_LOGOS_OK originals={len(manifest['assets'])}")
        return
    if not args.only:
        for old in OUT.glob('provider_catalog_*.png'):
            old.unlink()
    for entry in manifest['assets']:
        if args.only and entry['id'] != args.only: continue
        path = ASSETS / 'svg' / (entry['id']+'.svg')
        text = path.read_text()
        assert not re.search(r'<!ENTITY', text, re.I), path
        xml = ET.fromstring(re.sub(r'<!DOCTYPE[^>]*>', '', text, flags=re.I))
        for node in xml.iter():
            assert node.tag.rsplit('}', 1)[-1] not in ('script', 'foreignObject'), path
            for key, value in node.attrib.items():
                if 'href' not in key: continue
                assert value.startswith('#') or value.startswith('data:image/png;base64,'), path
                if value.startswith('data:'):
                    raw = base64.b64decode(value.split(',', 1)[1])
                    assert len(raw) <= 4 * 1024 * 1024 and raw.startswith(b'\x89PNG\r\n\x1a\n'), path
        for ref in re.findall(r'url\(([^)]*)\)', text):
            assert ref.strip(' \"\'').startswith('#'), path
        vb = xml.get('viewBox', '').split()
        if len(vb) == 4: w,h = float(vb[2]),float(vb[3])
        else:
            w = float(re.sub(r'[^0-9.]', '', xml.get('width', '24')))
            h = float(re.sub(r'[^0-9.]', '', xml.get('height', '24')))
        assert 0 < w <= 100000 and 0 < h <= 100000, path
        xml.set('width', str(w)); xml.set('height', str(h))
        key = 'provider_catalog_' + ''.join(c if c.isascii() and c.isalnum() else f'_{ord(c):02x}_' for c in entry['id'])
        dest = OUT / (key+'.png')
        with tempfile.TemporaryDirectory() as temp:
            safe = Path(temp) / 'input.svg'
            safe.write_text(ET.tostring(xml, encoding='unicode'))
            flag = '--export-width=192' if w >= h else '--export-height=192'
            subprocess.run(['inkscape',str(safe),flag,'--export-filename='+str(dest)],check=True,capture_output=True,timeout=45)
        image = Image.open(dest).convert('RGBA')
        assert max(image.size) <= 192 and image.getbbox(), path
        pixels = [p for p in image.getdata() if p[3] >= 40]
        mono = bool(pixels) and all(max(p[:3])-min(p[:3]) <= 10 for p in pixels) and max(p[0] for p in pixels)-min(p[0] for p in pixels) <= 32
        encoded = io.BytesIO()
        image.save(encoded,format='PNG',optimize=True)
        replacement = dest.with_suffix('.pending')
        replacement.write_bytes(encoded.getvalue())
        replacement.replace(dest)
        entry.update(resource=key,monochrome=mono,svg_sha256=digest(path),png_sha256=digest(dest))
    assets = manifest['assets']
    by_id = {e['id']:e['resource'] for e in assets}
    mapping = dict(by_id)
    for alias,target in manifest['aliases'].items():
        if alias not in mapping and target in mapping: mapping[alias]=mapping[target]
    kotlin = ['package com.baystudio.droide.ui','','import androidx.annotation.DrawableRes','import com.baystudio.droide.R','',
        '// Pinned upstream identity assets: third_party/models-dev-logos/manifest.json.',
        '@DrawableRes','internal fun providerCatalogLogo(id: String): Int? = when (id) {']
    kotlin += [f'    "{key}" -> R.drawable.{value}' for key,value in sorted(mapping.items())]
    kotlin += ['    else -> null','}','','internal fun isMonochromeProviderCatalogLogo(@DrawableRes logo: Int): Boolean = when (logo) {']
    mono = [e['resource'] for e in assets if e['monochrome']]
    kotlin += ['    '+',\n    '.join('R.drawable.'+key for key in mono)+' -> true','    else -> false','}','']
    (ROOT/'app/src/main/java/com/baystudio/droide/ui/ProviderCatalogBrand.kt').write_text('\n'.join(kotlin))
    (ASSETS/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f"Rendered {len(assets)} original provider marks")

if __name__ == '__main__': main()
