"""Build an installable release APK with a private, persistent local signing key."""
from pathlib import Path
import os
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SIGNING = ROOT / '.tools/signing'
SIGNING.mkdir(parents=True, exist_ok=True)
store = SIGNING / 'ghost-protocol.p12'
password_file = SIGNING / 'password.txt'
if not password_file.exists():
    password_file.write_text(secrets.token_urlsafe(32), encoding='utf-8')
    if os.name != 'nt':
        password_file.chmod(0o600)
env = os.environ.copy()
env['GHOST_SIGNING_STORE'] = str(store)
env['GHOST_SIGNING_PASSWORD'] = password_file.read_text(encoding='utf-8').strip()
if not store.exists():
    java = Path(shutil.which('java') or 'java')
    keytool = str(java.with_name('keytool.exe' if os.name == 'nt' else 'keytool'))
    subprocess.run([keytool, '-genkeypair', '-keystore', str(store), '-storetype', 'PKCS12',
        '-alias', 'ghost', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=Ghost Protocol Local Build, O=Ghost Protocol',
        '-storepass:env', 'GHOST_SIGNING_PASSWORD', '-keypass:env', 'GHOST_SIGNING_PASSWORD'],
        env=env, check=True)
gradle = [str(ROOT / 'gradlew.bat')] if os.name == 'nt' else ['sh', str(ROOT / 'gradlew')]
subprocess.run(gradle + [':core:test', ':app:assembleRelease', ':app:lintRelease', '--console=plain'],
    cwd=ROOT, env=env, check=True)
artifact = ROOT / 'artifacts/GhostProtocol.apk'
artifact.parent.mkdir(exist_ok=True)
shutil.copyfile(ROOT / 'app/build/outputs/apk/release/app-release.apk', artifact)
print(f'Installable release: {artifact}', flush=True)
