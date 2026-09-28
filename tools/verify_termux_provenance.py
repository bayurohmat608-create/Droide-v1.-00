#!/usr/bin/env python3
"""Offline provenance gate for the two locally pinned Termux terminal AARs."""
from __future__ import annotations
import hashlib, io, json, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
MANIFEST=ROOT/'third_party/termux/UPSTREAM_PROVENANCE.json'

def fail(msg:str)->None:
    raise SystemExit('TERMUX_PROVENANCE_FAILED: '+msg)

def sha256(data:bytes)->str:
    return hashlib.sha256(data).hexdigest()

def git_blob_sha1(data:bytes)->str:
    return hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()

def read_json(path:Path):
    try: return json.loads(path.read_text(encoding='utf-8'))
    except Exception as e: fail(f'invalid {path}: {e}')

m=read_json(MANIFEST)
if m.get('schemaVersion')!=1: fail('unsupported schema')
up=m.get('upstream',{})
if up.get('tag')!='v0.118.0' or up.get('commit')!='6e2689f55295fa444be8ac8592c527c2c5ef3253':
    fail('upstream tag/commit pin changed')
license_copy=ROOT/'app/src/main/assets/legal/licenses/Termux-upstream-LICENSE.md'
if git_blob_sha1(license_copy.read_bytes()) != up.get('rootLicenseGitBlobSha1'):
    fail('packaged upstream LICENSE.md is not byte-identical to v0.118.0')

source_top_classes=set()
source_count=0
for key in ('terminalEmulatorSources','terminalViewSources'):
    spec=m['artifacts'][key]
    path=ROOT/spec['path']
    raw=path.read_bytes()
    if sha256(raw)!=spec['sha256']: fail(f'{spec["path"]} SHA-256 mismatch')
    expected=spec['gitBlobSha1ByPath']
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        actual={n for n in z.namelist() if n.endswith('.java')}
        if actual != set(expected):
            fail(f'{path.name} source set mismatch: expected={len(expected)} actual={len(actual)}')
        for name,want in expected.items():
            data=z.read(name)
            got=git_blob_sha1(data)
            if got!=want: fail(f'{path.name}:{name} upstream git blob mismatch')
            source_top_classes.add(name[:-5]+'.class')
            source_count += 1
if source_count != m['constraints']['expectedJavaSourceFiles']:
    fail(f'expected {m["constraints"]["expectedJavaSourceFiles"]} upstream Java files, found {source_count}')

all_classes=set()
for key in ('terminalEmulatorAar','terminalViewAar'):
    spec=m['artifacts'][key]
    path=ROOT/spec['path']
    raw=path.read_bytes()
    if sha256(raw)!=spec['sha256']: fail(f'{spec["path"]} SHA-256 mismatch')
    with zipfile.ZipFile(io.BytesIO(raw)) as aar:
        if 'classes.jar' not in aar.namelist(): fail(f'{path.name} missing classes.jar')
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
            current={n for n in classes.namelist() if n.endswith('.class')}
            all_classes |= current
for prefix in m['constraints']['forbiddenClassPrefixes']:
    bad=sorted(c for c in all_classes if c.startswith(prefix))
    if bad: fail(f'forbidden class scope {prefix}: {bad[:3]}')
allowed_generated=set(m['constraints']['allowedGeneratedTopLevelClasses'])
for cls in all_classes:
    top=cls.split('$',1)[0]+'.class' if '$' in cls else cls
    if top not in source_top_classes and top not in allowed_generated:
        fail(f'AAR class has no verified upstream source mapping: {cls}')
missing=sorted(source_top_classes - all_classes)
if missing: fail('verified source file missing compiled top-level class: '+', '.join(missing))
if len(all_classes)!=m['constraints']['expectedAarClassFiles']:
    fail(f'expected {m["constraints"]["expectedAarClassFiles"]} AAR classes, found {len(all_classes)}')

em_path=ROOT/m['artifacts']['terminalEmulatorAar']['path']
with zipfile.ZipFile(em_path) as aar:
    actual_native={n:sha256(aar.read(n)) for n in aar.namelist() if n.startswith('jni/') and n.endswith('/libtermux.so')}
if actual_native != m['native']['libtermuxSha256ByAarPath']:
    fail('libtermux.so ABI set/hash mismatch')
java_spec=m['artifacts'].get('terminalEmulatorJavaAar')
if not java_spec: fail('missing stripped Java-only AAR provenance')
java_path=ROOT/java_spec['path']
java_raw=java_path.read_bytes()
if sha256(java_raw)!=java_spec['sha256']: fail(f'{java_spec["path"]} SHA-256 mismatch')
with zipfile.ZipFile(em_path) as full, zipfile.ZipFile(io.BytesIO(java_raw)) as stripped:
    full_non_jni={n for n in full.namelist() if not n.startswith('jni/')}
    stripped_names=set(stripped.namelist())
    if stripped_names != full_non_jni:
        fail('Java-only terminal AAR is not exactly the reviewed upstream AAR with JNI entries removed')
    for name in full_non_jni:
        if stripped.read(name) != full.read(name): fail(f'Java-only terminal AAR entry differs from upstream AAR: {name}')

view_path=ROOT/m['artifacts']['terminalViewAar']['path']
with zipfile.ZipFile(view_path) as aar:
    if any(n.startswith('jni/') and n.endswith('.so') for n in aar.namelist()):
        fail('terminal-view unexpectedly contains native libraries')
if set(m['native']['sourceGitBlobs']) != {'terminal-emulator/src/main/jni/termux.c','terminal-emulator/src/main/jni/Android.mk'}:
    fail('native source provenance set changed')

print(f'TERMUX_PROVENANCE_OK upstream={up["tag"]}@{up["commit"][:7]} java_sources={source_count} classes={len(all_classes)} native_abis={len(actual_native)}')
