#!/usr/bin/env python3
"""Check shipped dependency trust controls; Gradle performs actual artifact verification."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'v': 'https://schema.gradle.org/dependency-verification'}

def inspect(root: Path) -> dict:
    lock = root / 'app/gradle.lockfile'
    metadata = root / 'gradle/verification-metadata.xml'
    if not lock.is_file() or not metadata.is_file():
        raise ValueError('dependency lock state or verification metadata is missing; run tools/generate_dependency_trust.sh')
    properties = root / 'gradle.properties'
    if properties.is_file() and re.search(r'(?m)^\s*org\.gradle\.dependency\.verification\s*=\s*(off|lenient)\s*$', properties.read_text()):
        raise ValueError('dependency verification cannot be disabled or made lenient')
    rows = [s.strip() for s in lock.read_text().splitlines() if s.strip() and not s.startswith('#')]
    modules = []
    for row in rows:
        coordinates, separator, configurations = row.partition('=')
        if not separator:
            raise ValueError('malformed dependency lock row')
        if coordinates == 'empty':
            continue
        parts = coordinates.split(':')
        if len(parts) != 3 or not configurations or not all(parts):
            raise ValueError('lock entry must bind exact coordinates to configurations')
        if re.search(r'(?i)(SNAPSHOT|latest\.|[\[\](),*]|\+$)', parts[2]):
            raise ValueError('lock entry uses a changing or dynamic version')
        modules.append(coordinates)
    if not modules or len(modules) != len(set(modules)):
        raise ValueError('empty or duplicate module lock state')
    doc = ET.parse(metadata).getroot()
    if doc.tag != '{' + NS['v'] + '}verification-metadata':
        raise ValueError('unexpected dependency verification schema')
    if doc.findtext('v:configuration/v:verify-metadata', namespaces=NS) != 'true':
        raise ValueError('Gradle module metadata verification must be enabled')
    if doc.findall('v:configuration/v:trusted-artifacts/v:trust', NS):
        raise ValueError('unverified artifact exemptions are forbidden')
    artifacts = doc.findall('v:components/v:component/v:artifact', NS)
    if not artifacts:
        raise ValueError('no artifact checksums recorded')
    for artifact in artifacts:
        pins = artifact.findall('v:sha256', NS)
        if not pins or any(not re.fullmatch('[0-9a-f]{64}', p.get('value', '')) for p in pins):
            raise ValueError('every recorded artifact needs a valid SHA-256 pin')
        if any(not re.fullmatch('[0-9a-f]{64}', p.get('value', ''))
               for pin in pins for p in pin.findall('v:also-trust', NS)):
            raise ValueError('alternate artifact checksums must be valid SHA-256 values')
    coordinates = {':'.join(c.get(key, '') for key in ('group', 'name', 'version'))
                   for c in doc.findall('v:components/v:component', NS)}
    if set(modules) - coordinates:
        raise ValueError('locked modules are missing from checksum metadata')
    return {'lockedModules': len(modules), 'verifiedArtifacts': len(artifacts)}

if __name__ == '__main__':
    try:
        result = inspect(ROOT)
    except (ValueError, OSError, ET.ParseError) as failure:
        raise SystemExit('DEPENDENCY_TRUST_FAILED: ' + str(failure))
    print('DEPENDENCY_TRUST_OK', result)
