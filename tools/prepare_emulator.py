"""Prepare a local, isolated API 35 AVD using the official AOSP system image."""
from pathlib import Path
import hashlib
import os
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

base = 'https://dl.google.com/android/repository/sys-img/android/'
root = Path(os.environ['LOCALAPPDATA']) / 'StreamBrowserEmulator'
root.mkdir(parents=True, exist_ok=True)
tree = ET.fromstring(urllib.request.urlopen(base + 'sys-img2-3.xml').read())
package = next(p for p in tree if p.attrib.get('path') == 'system-images;android-35;default;x86_64')
archive = package.find('archives/archive/complete')
target = root / 'image.zip'
if not target.exists():
    print('Downloading official Android 35 image...', flush=True)
    urllib.request.urlretrieve(base + archive.findtext('url'), target.with_suffix('.download'))
    target.with_suffix('.download').replace(target)
with target.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha1').hexdigest()
if digest != archive.findtext('checksum'):
    raise RuntimeError('System image checksum mismatch')
if not (root / 'x86_64/system.img').exists():
    print('Extracting system image...', flush=True)
    with zipfile.ZipFile(target) as zip_file:
        zip_file.extractall(root)
avd = root / 'avd/StreamBrowserTest.avd'
avd.mkdir(parents=True, exist_ok=True)
(avd.parent / 'StreamBrowserTest.ini').write_text(f'avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-35\n')
(avd / 'config.ini').write_text(f'''AvdId=StreamBrowserTest
avd.ini.displayname=Stream Browser Test
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=3072
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.mainKeys=no
disk.dataPartition.size=4G
image.sysdir.1={root / 'x86_64'}
tag.id=default
tag.display=Default Android System Image
PlayStore.enabled=false
showDeviceFrame=no
''')
print(f'ANDROID_AVD_HOME={avd.parent}', flush=True)
