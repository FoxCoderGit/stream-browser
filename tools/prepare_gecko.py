"""Build a reproducible local GeckoView package with an in-engine observer."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = '160.0.20261116130455'
BASE = f'https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/{VERSION}/geckoview-{VERSION}'

def main():
    upstream = ROOT / 'gecko/upstream.aar'
    if not upstream.exists():
        partial = upstream.with_suffix('.download')
        urllib.request.urlretrieve(BASE + '.aar', partial)
        partial.replace(upstream)
    digest = hashlib.sha256(upstream.read_bytes()).hexdigest()
    expected = urllib.request.urlopen(BASE + '.aar.sha256').read().decode().split()[0]
    if digest != expected:
        raise RuntimeError('Upstream AAR checksum mismatch')
    with zipfile.ZipFile(upstream) as aar:
        omni_name = next(n for n in aar.namelist() if n.endswith('/omni.ja'))
        with zipfile.ZipFile(io.BytesIO(aar.read(omni_name))) as omni:
            startup = next(n for n in omni.namelist() if n.endswith('/GeckoViewStartup.sys.mjs'))
            original = omni.read(startup)
            messaging = omni.read('modules/Messaging.sys.mjs')
            if b'sendRequest(aType, aMsg, aCallback)' not in messaging:
                raise RuntimeError('Gecko event bridge API changed; review the engine adapter')
            (ROOT / 'gecko/GeckoViewStartup.upstream.sys.mjs').write_bytes(original)
            modified = original + b'\n' + (ROOT / 'gecko/stream-observer.js').read_bytes()
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as result:
                for item in omni.infolist():
                    # Precompiled startup data must not shadow the modified module.
                    if 'startupcache' in item.filename.lower():
                        continue
                    result.writestr(item.filename, modified if item.filename == startup else omni.read(item))
                for actor in sorted((ROOT / 'gecko').glob('StreamSelection*.sys.mjs')):
                    result.writestr('modules/' + actor.name, actor.read_bytes())
        local_version = VERSION + '-stream2'
        target = ROOT / f'gecko/repository/local/gecko/geckoview/{local_version}'
        target.mkdir(parents=True, exist_ok=True)
        stem = target / f'geckoview-{local_version}'
        with zipfile.ZipFile(str(stem) + '.aar', 'w', zipfile.ZIP_DEFLATED) as result:
            for item in aar.infolist():
                result.writestr(item.filename, buffer.getvalue() if item.filename == omni_name else aar.read(item))
    pom = urllib.request.urlopen(BASE + '.pom').read()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    ET.register_namespace('', ns['m'])
    tree = ET.fromstring(pom)
    tree.find('m:groupId', ns).text = 'local.gecko'
    tree.find('m:version', ns).text = local_version
    ET.ElementTree(tree).write(str(stem) + '.pom', encoding='utf-8', xml_declaration=True)
    (ROOT / 'gecko/provenance.json').write_text(json.dumps({
        'upstream': BASE + '.aar', 'sha256': digest, 'module': startup,
        'patchSha256': hashlib.sha256(modified).hexdigest(),
        'method': 'engine module patch; native libxul unchanged; no WebExtension'
    }, indent=2) + '\n')
    print('Prepared', stem.name)

if __name__ == '__main__':
    main()
