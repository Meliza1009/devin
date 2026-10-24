"""Run production Bluetooth sessions across Android runtimes sharing a BLE environment.

For emulators, launch each with -feature BluetoothEmulation. Physical devices
must be in the same room. This script resets the test app on the named devices.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import threading

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--serials',nargs='+',required=True)
parser.add_argument('--lobby-only',action='store_true')
parser.add_argument('--reuse-installed',action='store_true',help='Use the already installed app/test APKs.')
parser.add_argument('--prepare-isolated-emulators',action='store_true',help='Disable background Google apps only in GhostPeer test profiles.')
fault_modes=parser.add_mutually_exclusive_group()
fault_modes.add_argument('--interrupt-last-peer',action='store_true',help='Test an 18-second Bluetooth interruption on the last listed device.')
fault_modes.add_argument('--abandon-last-peer',action='store_true',help='Test timeout and late reconnection after 75 seconds without Bluetooth.')
args=parser.parse_args()
if not args.lobby_only and not 4<=len(args.serials)<=8:
    parser.error('A game needs 4–8 Android runtimes; use --lobby-only for two.')
fault_requested=args.interrupt_last_peer or args.abandon_last_peer
if fault_requested and args.lobby_only:
    parser.error('Fault scenarios require a running game')
if len(set(args.serials))!=len(args.serials):
    parser.error('Every device serial must be distinct')
scenario='lobby' if args.lobby_only else 'timeout' if args.abandon_last_peer else 'reconnect' if args.interrupt_last_peer else 'round'
sdk=Path(os.environ.get('ANDROID_HOME',Path.home()/'AppData/Local/Android/Sdk'))
adb=str(sdk/'platform-tools/adb.exe')
artifacts=ROOT/'artifacts'
artifacts.mkdir(exist_ok=True)
code=''.join(secrets.choice('0123456789ABCDEFGHJKMNPQRSTVWXYZ') for _ in range(20))
fault_lock=threading.Lock()
fault_started=False
fault_finished=threading.Event()
fault_errors=[]
paused_peers=set()
resumed_peers=set()

def interrupt_peer():
    try:
        target=args.serials[-1]
        command(target,'shell','svc','bluetooth','disable')
        duration=75 if args.abandon_last_peer else 18
        print(f'Fault test: {target} Bluetooth off for {duration} seconds',flush=True)
        time.sleep(duration)
        command(target,'shell','svc','bluetooth','enable')
        fault_finished.set()
        print(f'Fault test: {target} Bluetooth restored',flush=True)
    except Exception as error:
        fault_errors.append(str(error))

def observe(serial,state):
    global fault_started
    if not fault_requested:
        return
    with fault_lock:
        if not fault_started and state.get('elapsed_ms',0)>=35000:
            fault_started=True
            threading.Thread(target=interrupt_peer,daemon=True).start()
        missing=state.get('missing') not in (None,'[]',[])
        if fault_started and missing:
            paused_peers.add(serial)
        recovered=(state.get('phase')=='ENDED' and state.get('winner')=='NONE') if args.abandon_last_peer else state.get('elapsed_ms',0)>45000
        if fault_finished.is_set() and not missing and recovered:
            resumed_peers.add(serial)

def command(serial,*arguments,check=True):
    return subprocess.run([adb,'-s',serial,*arguments],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                          text=True,encoding='utf-8',errors='replace',check=check,timeout=60).stdout

def setup(serial):
    deadline=time.monotonic()+600
    while command(serial,'get-state',check=False).strip()!='device':
        if time.monotonic()>deadline:
            raise TimeoutError(f'{serial} did not connect to ADB')
        time.sleep(1)
    if args.prepare_isolated_emulators:
        profile=command(serial,'shell','getprop','ro.boot.qemu.avd_name').strip()
        if not serial.startswith('emulator-') or not profile.startswith('GhostPeer'):
            raise ValueError('Background preparation is only allowed in isolated GhostPeer profiles')
    deadline=time.monotonic()+600
    while command(serial,'shell','getprop','sys.boot_completed',check=False).strip()!='1':
        if time.monotonic()>deadline:
            raise TimeoutError(f'{serial} did not finish booting')
        if serial.startswith('emulator-'):
            command(serial,'shell','settings','put','global','device_provisioned','1',check=False)
            command(serial,'shell','settings','put','secure','user_setup_complete','1',check=False)
            command(serial,'shell','input','keyevent','KEYCODE_WAKEUP',check=False)
        time.sleep(1)
    if args.prepare_isolated_emulators:
        for package in ('com.google.android.apps.youtube.music','com.google.android.youtube',
                'com.google.android.googlequicksearchbox','com.google.android.apps.photos',
                'com.google.android.gm','com.google.android.apps.maps','com.android.chrome',
                'com.google.android.gms','com.android.vending','com.google.android.apps.wellbeing',
                'com.google.android.as','com.google.android.as.oss','com.google.android.settings.intelligence'):
            command(serial,'shell','pm','disable-user','--user','0',package,check=False)
        for setting in ('window_animation_scale','transition_animation_scale','animator_duration_scale'):
            command(serial,'shell','settings','put','global',setting,'0')
    if not args.reuse_installed:
        command(serial,'uninstall','com.ghostprotocol.app.test',check=False)
        command(serial,'uninstall','com.ghostprotocol.app',check=False)
        command(serial,'install',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
        command(serial,'install',str(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    level=int(command(serial,'shell','getprop','ro.build.version.sdk').strip())
    permissions=['ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION']
    if level>=31:
        permissions+=['BLUETOOTH_SCAN','BLUETOOTH_ADVERTISE','BLUETOOTH_CONNECT']
    for permission in permissions:
        command(serial,'shell','pm','grant','com.ghostprotocol.app','android.permission.'+permission)
    command(serial,'shell','cmd','location','set-location-enabled','true')
    command(serial,'shell','svc','bluetooth','enable')
    command(serial,'shell','input','keyevent','KEYCODE_WAKEUP')
    command(serial,'shell','wm','dismiss-keyguard')
    command(serial,'shell','settings','put','system','screen_off_timeout','1800000')
    deadline=time.monotonic()+180
    while 'enabled: true' not in command(serial,'shell','dumpsys','bluetooth_manager'):
        if time.monotonic()>deadline:
            raise TimeoutError(f'{serial} Bluetooth did not become ready')
        time.sleep(1)
    print(f'{serial}: ready',flush=True)

def run_peer(item):
    index,serial=item
    process=subprocess.Popen([adb,'-s',serial,'shell','am','instrument','-w','-r',
        '-e','mode','mesh','-e','expected',str(len(args.serials)),'-e','host',str(index==0).lower(),
        '-e','lobby',str(args.lobby_only).lower(),'-e','name',f'Peer{index+1}','-e','code',code,
        '-e','winner','NONE' if args.abandon_last_peer else 'HUNTERS',
        '-e','holdResultMs','35000' if args.abandon_last_peer else '5000',
        'com.ghostprotocol.app.test/com.ghostprotocol.app.SmokeInstrumentation'],
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    lines=[];result=None
    for line in process.stdout:
        lines.append(line)
        if 'RADIO_PROGRESS ' in line:
            state=json.loads(line.split('RADIO_PROGRESS ',1)[1])
            observe(serial,state)
            print(f"{serial}: {state.get('phase',state['screen'])} members={state['members']} links={state['links']} revision={state['revision']} elapsed={state.get('elapsed_ms',0)//1000}s missing={state['missing']}",flush=True)
        elif 'RADIO_' in line:
            print(f'{serial}: {line.strip()}',flush=True)
        if 'RADIO_RESULT ' in line:
            result=json.loads(line.split('RADIO_RESULT ',1)[1])
    process.wait()
    (artifacts/f'mesh-{scenario}-{serial}.txt').write_text(''.join(lines),encoding='utf-8')
    return serial,result

with ThreadPoolExecutor(max_workers=len(args.serials)) as pool:
    list(pool.map(setup,args.serials))
    results=dict(pool.map(run_peer,enumerate(args.serials)))
assert all(results.values()),'At least one Android runtime failed; inspect artifacts/mesh-*.txt'
if args.abandon_last_peer:
    assert all(result['winner']=='NONE' and result['phase']=='ENDED' for result in results.values())
elif not args.lobby_only:
    assert len({result['digest'] for result in results.values()})==1,'Final state diverged'
    assert all(result['winner']=='HUNTERS' for result in results.values())
    ghost=next(result for result in results.values() if result['ghost'])
    assert ghost['fade_used'] and ghost['echo_used'],'Ghost abilities were not exercised'
if fault_requested:
    assert fault_started and fault_finished.is_set() and not fault_errors,f'Fault did not complete: {fault_errors}'
    assert paused_peers==set(args.serials),f'Pause not observed by every phone: {paused_peers}'
    assert resumed_peers==set(args.serials),f'Recovery not observed by every phone: {resumed_peers}'
output=artifacts/('mesh-lobby.json' if args.lobby_only else f'mesh-{len(args.serials)}-phones'+('-timeout' if args.abandon_last_peer else '-reconnect' if args.interrupt_last_peer else '')+'.json')
output.write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
if fault_requested:
    output.with_suffix('.observations.json').write_text(json.dumps({'duration_seconds':75 if args.abandon_last_peer else 18,'paused_peers':sorted(paused_peers),'reconnected_peers':sorted(resumed_peers),'late_reconnection_revived_round':False if args.abandon_last_peer else None},indent=2)+'\n',encoding='utf-8')
print(f'PASS: {output}',flush=True)
