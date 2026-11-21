"""Package CLI binaries only. Never include the GPL Android wrapper classes."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = '0.18.1'

def main():
    output = ROOT / 'app/runtime'
    assets = output / 'assets/runtime'
    assets.mkdir(parents=True, exist_ok=True)
    provenance = []
    for module in ('library', 'ffmpeg', 'aria2c'):
        base = f'https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/{module}/{VERSION}/{module}-{VERSION}.aar'
        cache = Path.home() / f'.gradle/caches/modules-2/files-2.1/io.github.junkfood02.youtubedl-android/{module}/{VERSION}'
        cached = list(cache.rglob('*.aar'))
        data = cached[0].read_bytes() if cached else urllib.request.urlopen(base).read()
        sha = hashlib.sha256(data).hexdigest()
        expected = urllib.request.urlopen(base + '.sha256').read().decode().split()[0]
        if sha != expected:
            raise RuntimeError('Checksum mismatch: ' + module)
        provenance.append({'source': base, 'sha256': sha, 'extracted': 'CLI executable payloads and their dependencies; no classes.jar'})
        with zipfile.ZipFile(io.BytesIO(data)) as aar:
            if module == 'library':
                (assets / 'yt-dlp.zip').write_bytes(aar.read('res/raw/ytdlp'))
            for abi in ('arm64-v8a', 'x86_64'):
                for name in aar.namelist():
                    if not name.startswith(f'jni/{abi}/') or not name.endswith('.so'):
                        continue
                    target = output / 'jniLibs' / abi / Path(name).name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    payload = aar.read(name)
                    target.write_bytes(payload)
                    if name.endswith('.zip.so'):
                        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
                            links = {i.filename: archive.read(i).decode() for i in archive.infolist()
                                     if (i.external_attr >> 16) & 0o170000 == 0o120000}
                            (assets / f'{abi}-{Path(name).name}.links.json').write_text(json.dumps(links))
    (ROOT / 'gecko/runtime-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print('Packaged standalone Python, yt-dlp, QuickJS and FFmpeg; GPL wrapper excluded.')

if __name__ == '__main__':
    main()
