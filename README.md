# Ghost Protocol

A native Android social deduction game for 4–8 people in one room. One phone belongs to the ghost. Everyone else compares delayed Bluetooth signal clues and encounter history, then uses shared evidence and a majority vote to identify them before eight minutes expire.

## Play

Install the same APK on every phone. Android 9 or later and Bluetooth LE scanning/advertising are required for nearby play. No Google Play Services, account, Wi-Fi, internet connection, or server is used.

1. Enter a callsign. One player creates a room and privately shares its 20-character key.
2. Other players join with that key. Allow Nearby devices and precise location access when Android asks. Enable Bluetooth and Location; the app uses Bluetooth signal strength, not GPS.
3. With 4–8 phones present, the creator assigns roles. Shield your screen, read your role, then enter the hunt. The round waits for everyone to be ready.
4. Walk around normally. Compare the last six delayed signal readings with your encounter history. Use **People** to nominate a nearby suspect and **Graph** to inspect shared connections.
5. A nomination requires two other observers and three distinct signal intervals within 90 seconds. A strict majority of the non-suspects must confirm it within 15 seconds. A wrong conviction costs 45 seconds, clears the suspect, and restores a ghost charge.

Ghosts start with two charges. **Fade** reduces their signal for 20 seconds. **Echo** makes a recently encountered player also contribute misleading clues for 20 seconds. Abilities share a 30-second cooldown. Access them through **Private role tools**.

Keep the app open and the phones awake. The app keeps the screen on during a session. If a phone leaves, shared progression stops; after ten seconds the room displays a reconnect pause, then allows another 60 seconds before ending without a winner. A process that is killed cannot restore its room: session state and room keys exist only in memory.

**Training room** works on one phone with five simulated players. Choose either role and use **Move area** to change encounters. It is explicitly a simulation and does not test Bluetooth connectivity.

## Build

Open the project in Android Studio, or use the checked-in Gradle wrapper with JDK 17–25, Android SDK 36 and Build Tools 36.0.0. Set `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir=/path/to/android/sdk`.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

On macOS/Linux, run `sh gradlew` instead. The wrapper pins Gradle 9.3.1 and checks its SHA-256. The initial build downloads development dependencies; the installed app has no internet permission and uses no network service.

The installable development APK is `app/build/outputs/apk/debug/app-debug.apk`.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For an installable release build, run `python tools/package.py`. It produces `artifacts/GhostProtocol.apk` and creates a private signing key under the ignored `.tools/signing/` directory. Preserve that directory to sign compatible future updates. The release APK is not debuggable and contains no test runner. Store publishing and store-specific signing are separate from this local package.

## Verify

`core` is a platform-independent Java module. Its tests exercise voting, evidence expiry, clock limits, abilities, encrypted envelopes, fragmentation, and replication under disconnection. The Android app uses native views and the Android Bluetooth APIs directly.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tools/ui_smoke.py --serial emulator-5554
```

The native instrumentation exercises both private role reveals, timer updates during dense callbacks, all three investigation tabs, training movement, Fade and Echo, and accusation through debrief. The final scenario prepares evidence in memory to reach the vote without waiting several minutes. It saves screenshots and results under `artifacts/`. Its runner belongs to the separate test APK and is absent from the game APK.

The same test APK also has a Bluetooth integration mode. With four unlocked Android devices connected by ADB and in the same room, run:

```powershell
python tools/mesh_smoke.py --serials DEVICE_1 DEVICE_2 DEVICE_3 DEVICE_4
```

This resets the test app on the named devices, grants its declared radio permissions, and drives the production room/session code through discovery, role assignment, Fade, Echo, and a group accusation. It checks matching final state digests and saves per-device logs. Use `--lobby-only` for a two-device discovery and encrypted-message test.

Add `--interrupt-last-peer` to disable Bluetooth on the last listed device for 18 seconds and verify pause, recovery, and a consistent result. Add `--abandon-last-peer` instead to test a 75-second absence and confirm that late reconnection cannot revive the ended round. These scenarios save their results and observations separately.

For isolated virtual phones on Windows, `tools/start_mesh_emulators.ps1 -Count 4` creates test profiles under `.tools/avds/` and enables the emulator's `BluetoothEmulation` feature. Multiple Android runtimes require substantial host memory. `--prepare-isolated-emulators` disables background Google apps only inside these disposable `GhostPeer` profiles; it is never applied to physical phones.

## Architecture and limits

- `core`: deterministic rules, bounded binary state/event encoding, AES-256-GCM room encryption, ATT packet framing, and all-member prepare/ack/commit replication.
- `app`: Bluetooth LE discovery and GATT transport, session liveness, proximity sampling, a local simulator, and native UI.
- Every phone maintains the committed round state. The creator's phone sequences proposed events; all participants must acknowledge a proposed state before it commits. The creator has no external server dependency. Its absence pauses the round just like any other phone.
- Advertisements contain a room fingerprint, a session pseudonym, and a rotating keyed signature. They expose no name or role. Encrypted packets are relayed through the local mesh, deduplicated, fragmented to the negotiated ATT MTU, and retransmitted at the application level when needed.
- This is trusted party play. The shared room key protects traffic from outsiders; participants use a common encrypted state and a modified client could inspect roles or forge actions. This is not a competitive anti-cheat protocol.
- RSSI is intentionally approximate. Walls, body position, cases, and phone models affect readings. Real-device playtests are required to validate radio interoperability and game balance across hardware.

## Remaining device checks

All 25 core tests and eight native UI scenarios pass. Debug and release lint report zero issues. The signed release APK was installed and launched on Android 14; its signature verifies, it is not debuggable, and it contains neither internet permission nor test instrumentation. See `artifacts/verification.json` for the APK checksum and evidence files.

Four Android runtimes also passed a complete Bluetooth round, recovery after an 18-second interruption, and termination after a 75-second absence. Every scenario produced matching final state digests, and late reconnection left the abandoned round ended. These tests use Android Bluetooth emulation; actual multi-phone play on physical radios remains unverified.

Use four phones first, then repeat with eight:

1. Install `artifacts/GhostProtocol.apk` on each phone. Turn off Wi-Fi/mobile data, keep Bluetooth and Location enabled, and join one room. Confirm the creator sees every callsign before assigning roles.
2. Read roles privately and enter the hunt. Confirm the timer waits for everyone, the first clue appears after ten seconds, and tagging stays locked for the 30-second calibration.
3. Walk between groups. Compare delayed clues, encounter histories, and the shared graph. Exercise a correct accusation, a wrong accusation, and Fade/Echo across separate rounds.
4. Turn off Bluetooth on one phone. Confirm the other phones pause after ten seconds. Restore Bluetooth within another 60 seconds and check that the same round resumes with matching state.
5. Repeat the interruption for longer than 70 seconds. Confirm the round ends without a winner and late reconnect messages do not revive it.

The release is locally signed and installable. It has no internet permission. It has not been published to an app store.

Implementation references: [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [BLE scanning](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices), [GATT server callbacks](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer), and [AGP compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes).
