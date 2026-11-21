"""Check the actual installable package, not only generated build directories."""
from pathlib import Path
import hashlib
import io
import json
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
path = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'artifacts/StreamBrowser-debug.apk'
with zipfile.ZipFile(path) as apk:
    with zipfile.ZipFile(io.BytesIO(apk.read('assets/omni.ja'))) as omni:
        provenance = json.loads((root / 'gecko/provenance.json').read_text())
        startup = omni.read(provenance['module'])
        assert hashlib.sha256(startup).hexdigest() == provenance['patchSha256'], 'Engine patch absent/stale'
        assert b'sendRequest("Stream:Response",' in startup, 'Wrong bridge protocol'
    abis = sorted({name.split('/')[1] for name in apk.namelist() if name.startswith('lib/')})
    assert set(abis) == {'arm64-v8a', 'x86_64'}
    for abi in abis:
        for name in ('libstream_core.so', 'libpython.so', 'libqjs.so', 'libffmpeg.so', 'libaria2c.so'):
            assert apk.read(f'lib/{abi}/{name}').startswith(b'\x7fELF'), (abi, name)
        for package in ('python', 'ffmpeg', 'aria2c'):
            name = f'lib{package}.zip.so'
            links = json.loads(apk.read(f'assets/runtime/{abi}-{name}.links.json'))
            with zipfile.ZipFile(io.BytesIO(apk.read(f'lib/{abi}/{name}'))) as archive:
                assert all(entry in archive.namelist() for entry in links)
    assert apk.read('assets/runtime/yt-dlp.zip').startswith(b'PK') or b'__main__' in apk.read('assets/runtime/yt-dlp.zip')
    for name in apk.namelist():
        if name.endswith('.dex'):
            assert b'Lcom/yausername/' not in apk.read(name), 'GPL Android wrapper unexpectedly linked'
print(json.dumps({'apk': path.name, 'sizeMiB': round(path.stat().st_size / 1048576, 1),
    'abis': abis, 'enginePatch': 'verified', 'wrapperClasses': 'absent',
    'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}, indent=2))
