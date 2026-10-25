"""Run the installed instrumentation APK and collect its screenshots.

Build/install app-debug.apk and app-debug-androidTest.apk first.
Run: python tools/ui_smoke.py --serial emulator-5554
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / 'artifacts'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'AppData/Local/Android/Sdk'))
adb = [str(sdk / 'platform-tools/adb.exe'), '-s', args.serial]
process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r',
    'com.ghostprotocol.app.test/com.ghostprotocol.app.SmokeInstrumentation'],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8')
lines = []
for line in process.stdout:
    print(line, end='', flush=True)
    lines.append(line)
process.wait()
ARTIFACTS.mkdir(exist_ok=True)
(ARTIFACTS / 'heist-ui-smoke.txt').write_text(''.join(lines), encoding='utf-8')
subprocess.run(adb + ['pull', '/sdcard/Android/data/com.ghostprotocol.app/files/heist-smoke/.', str(ARTIFACTS)], check=False)
sys.exit(0 if 'GhostProtocol heist: PASS' in ''.join(lines) else 1)
