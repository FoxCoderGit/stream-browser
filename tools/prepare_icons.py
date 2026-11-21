"""Import Google's Material Symbols Rounded SVGs as Android vector drawables."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
REVISION = '10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7'
BASE = f'https://raw.githubusercontent.com/google/material-design-icons/{REVISION}'
ICONS = (
    'arrow_back', 'arrow_forward', 'arrow_downward', 'chevron_right', 'close',
    'download', 'graphic_eq', 'language', 'link', 'more_horiz',
    'movie', 'refresh', 'settings', 'video_library', 'home', 'search',
    'history', 'folder', 'create_new_folder', 'bookmark_add', 'delete',
)


def import_symbol(name):
    source = f'{BASE}/symbols/web/{name}/materialsymbolsrounded/{name}_24px.svg'
    with urlopen(source, timeout=20) as response:
        svg = ET.fromstring(response.read())
    assert svg.attrib['viewBox'] == '0 -960 960 960'
    paths = list(svg)
    assert paths and all(node.tag.endswith('}path') and set(node.attrib) == {'d'} for node in paths)
    drawable = ROOT / f'app/src/main/res/drawable/ms_{name}.xml'
    lines = [
        '<!-- Material Symbols Rounded, Google. Apache-2.0. Converted from SVG. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp" android:height="24dp"',
        '    android:viewportWidth="960" android:viewportHeight="960">',
        '    <group android:translateY="960">',
    ]
    lines.extend(f'        <path android:fillColor="#FFFFFFFF" android:pathData="{node.attrib["d"]}"/>' for node in paths)
    lines.extend(('    </group>', '</vector>'))
    drawable.write_text('\n'.join(lines) + '\n', encoding='utf-8')


if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(import_symbol, ICONS))
    with urlopen(BASE + '/LICENSE', timeout=20) as response:
        (ROOT / 'LICENSE-MATERIAL-SYMBOLS.txt').write_bytes(response.read())
