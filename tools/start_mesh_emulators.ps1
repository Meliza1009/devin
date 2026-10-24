param([ValidateRange(2,8)][int]$Count = 2, [ValidateRange(768,8192)][int]$MemoryMB = 1024)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$avdRoot = Join-Path $projectRoot '.tools/avds'
$imageRoot = Join-Path $sdkRoot 'system-images/android-34/google_apis/x86_64'
if (-not (Test-Path -LiteralPath (Join-Path $imageRoot 'system.img'))) { throw 'Install system-images;android-34;google_apis;x86_64 first.' }
New-Item -ItemType Directory -Path $avdRoot -Force | Out-Null
$env:ANDROID_AVD_HOME = $avdRoot
$activeDevices = & (Join-Path $sdkRoot 'platform-tools/adb.exe') devices
for ($index = 1; $index -le $Count; $index++) {
    $peerName = "GhostPeer$index"
    $peerDirectory = Join-Path $avdRoot "$peerName.avd"
    $peerPort = 5552 + 2 * $index
    if ($activeDevices -match "emulator-$peerPort\s+device") {
        $currentProfile = & (Join-Path $sdkRoot 'platform-tools/adb.exe') -s "emulator-$peerPort" shell getprop ro.boot.qemu.avd_name
        if ($currentProfile.Trim() -ne $peerName) { throw "Port $peerPort belongs to another emulator profile." }
        Write-Output "emulator-$peerPort is already running"
        continue
    }
    New-Item -ItemType Directory -Path $peerDirectory -Force | Out-Null
    @"
avd.ini.encoding=UTF-8
path=$peerDirectory
target=android-34
"@ | Set-Content -LiteralPath (Join-Path $avdRoot "$peerName.ini") -Encoding UTF8
    @"
AvdId=$peerName
avd.ini.displayname=$peerName
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=$MemoryMB
hw.lcd.width=480
hw.lcd.height=800
hw.lcd.density=160
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.audioInput=no
hw.audioOutput=no
disk.dataPartition.size=2G
image.sysdir.1=$imageRoot/
tag.id=google_apis
tag.display=Google APIs
PlayStore.enabled=false
fastboot.forceColdBoot=yes
"@ | Set-Content -LiteralPath (Join-Path $peerDirectory 'config.ini') -Encoding UTF8
    Start-Process -FilePath (Join-Path $sdkRoot 'emulator/emulator.exe') -ArgumentList '-avd',$peerName,'-port',"$peerPort",'-no-window','-no-snapshot','-no-boot-anim','-feature','BluetoothEmulation','-gpu','swiftshader_indirect','-memory',"$MemoryMB",'-no-audio' -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot "artifacts/peer-$peerPort.log") -RedirectStandardError (Join-Path $projectRoot "artifacts/peer-$peerPort-error.log")
    Write-Output "${peerName}: emulator-$peerPort starting"
}
