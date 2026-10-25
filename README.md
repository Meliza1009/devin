# Ghost Protocol: The Haunted Heist

A native, offline Android game for **4–8 people**, three physical checkpoints, and one hidden ghost. The crew must recover three relics, seal them, deliver them, and escape. Identifying the ghost starts a showdown; it does not finish the heist.

## Set up the room

1. Install the same **2.0** APK on every Android phone. Android 9 or later is required. Nearby play needs Bluetooth LE scanning and advertising, plus a camera for checkpoint scans.
2. Print [the three checkpoint cards](artifacts/GhostProtocol-Checkpoints.pdf), or display them on spare screens. Place **Vault**, **Workshop**, and **Exit** at distinct points with a clear walking route.
3. Open **Learn by Doing** for a short interactive explanation. **Practice** uses virtual checkpoints and simulated partners on one phone.
4. Enter a callsign. One player chooses **Play Heist → Create a heist**; everyone else chooses **Join a heist** and enters the creator's private 20-character room key.
5. Enable Bluetooth and Android Location and allow nearby-device permissions. Location is required for Bluetooth proximity scanning; the game does not use GPS. Allow camera access when scanning a card.
6. The creator opens **Set up / verify room** and scans all three placed checkpoints. With everyone present, assign roles. Read your allegiance privately, hide it, then enter the heist.

No internet, account, Wi-Fi connection, external server, or Google Play Services is needed. The clock starts when the whole crew is ready. Keep every game open and phone awake; walk rather than run.

## Play the heist

**Mission** always shows your next useful action. The pinned display shows time, Alarm, and delivered relics.

1. **Recover at the Vault:** Scan the card and start a breach. A nearby teammate scans the same card and joins. The reader sees a three-symbol sequence; the operator enters it. Communicate in order, then submit. A wrong sequence or active puzzle timeout adds **5 Alarm**.
2. **Seal at the Workshop:** Carry the recovered relic there, scan the card, and begin the six-second sealing action. A corrupted relic must be sealed again.
3. **Deliver at the Exit:** Scan the Exit card and deposit a sealed relic. Deposited relics are safe from sabotage.
4. **Escape together:** After all three relics arrive, two players solve the final Exit puzzle. The hunters win even if the ghost has never been identified.

You carry one relic; a Courier carries two. Use **Gear** to offer a nearby handoff. The recipient must accept. A relic held for over 60 seconds can be recalled through a paired Vault task at a cost of **10 Alarm**, preventing stalled loot from stopping the match.

Each checkpoint scan remains fresh for 15 seconds. Waiting operators can rescan without canceling their task. A task waits up to 30 seconds for a partner, then gives the pair 30 seconds to solve it. Scanning another checkpoint cancels your task. Disabled controls explain what is missing.

**Crew** contains encounter histories, private suspicion markers, and dossiers with a shared evidence graph. Signals are delayed and noisy. Connections and strong-signal overlaps are clues, not a guilt percentage.

## Specialties and sabotage

Your specialty is public; your allegiance is private. Every essential heist action is available without a particular specialty.

| Specialty | Equipment |
| --- | --- |
| Analyst | Reveal the next puzzle symbol to the operator; 60-second recharge. |
| Engineer | Scan a jammed checkpoint and bypass its jam; 90-second recharge. |
| Courier | Two inventory slots, always available. |
| Warden | Protect yourself or a nearby carrier for 30 seconds; 60-second recharge. |

The ghost has an ordinary specialty as cover. Open **Private briefing** for secret tools. Start with two energy, hold up to three, and regenerate one every 30 seconds. Sabotage shares a 20-second cooldown and adds five Alarm.

- **Jam — one energy:** Secretly disable a recently scanned checkpoint for 25 seconds. Hunters can repair it with a paired puzzle or an Engineer. The checkpoint then has protection against immediate repeated jams.
- **Corrupt — two energy:** Approach another carrier and turn their sealed relic into an unsealed one. Newly sealed relics have 30 seconds of protection; Warden protection also blocks corruption.
- **After exposure:** The ghost keeps playing, regenerates energy every 20 seconds, can jam remotely, and can **Haunt** a nearby hunter for one energy. Haunt interrupts relic actions for eight seconds, followed by 30 seconds of immunity. The ghost's carried loot returns to Workshop storage.

The crew has **two accusations**, unlocked after 90 seconds. Start one from a person's dossier. A 20-second private vote needs a strict majority of non-suspects. A wrong conviction clears the player and adds **20 Alarm**. A failed vote still spends the opportunity. Correctly exposing the ghost starts the showdown; the crew still needs to escape.

The ghost wins when the ten-minute clock expires or Alarm reaches 100. Nobody is eliminated. A paired Workshop purge reduces Alarm by 15 and recharges in 60 seconds.

At minutes two, four, and six, a shuffled event changes the room for 20 seconds: **Blackout** hides signal clues; **Overclock** gives new puzzles a hint and 45 seconds; **Lockdown** temporarily closes the Vault or Workshop to new tasks. Existing puzzles continue.

## Connection and comfort

If a phone leaves, shared progression stops. After ten seconds the room shows a reconnect pause; a further 60-second absence ends the heist without a winner. Restore Bluetooth and return to the same running app. Late messages cannot revive an ended round. Killing the app process loses its in-memory session.

Settings offers **Reduce motion** and optional action sounds. Sound is off by default. Android's larger text settings are supported. Secret tools use the same neutral action feedback as other controls.

This is trusted party play. The shared room key encrypts traffic against outsiders; a modified participant client could inspect the replicated secret roles. Checkpoint cards establish game actions rather than exact indoor positioning.

## Build and verify

Use the checked-in Gradle wrapper with JDK 17–25, Android SDK 36, and Build Tools 36.0.0. Set `ANDROID_HOME` or an ignored `local.properties` with `sdk.dir=/path/to/android/sdk`.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

On macOS/Linux, use `sh gradlew`. The first build downloads development dependencies; the installed app operates offline. QR decoding is bundled in the APK.

Install the debug game and separate instrumentation APK, then run the native touch tests:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tools/ui_smoke.py --serial emulator-5554
adb shell am instrument -w -r -e mode ui-regression com.ghostprotocol.app.test/com.ghostprotocol.app.SmokeInstrumentation
```

The native scenarios cover the guide, QR/PDF generation, private roles, stable controls under dense callbacks, complete relic recovery through extraction, ghost showdown, camera permissions, waiting-task rescans, safe live help, and larger text. Screenshots and logs are collected in `artifacts/`.

For production Bluetooth integration with four nearby Android devices connected over ADB:

```powershell
python tools/mesh_smoke.py --serials DEVICE_1 DEVICE_2 DEVICE_3 DEVICE_4
```

This resets only the named test apps and drives production sessions through actual BLE discovery and replicated heist actions. The test supplies checkpoint scan events; physical camera scanning needs a separate room playtest. Add `--interrupt-last-peer` for an 18-second Bluetooth outage or `--abandon-last-peer` for a 75-second absence and late reconnection. `--lobby-only` supports a two-device discovery test.

Windows emulator setup: `tools/start_mesh_emulators.ps1 -Count 4` creates isolated profiles under `.tools/avds/` with Bluetooth emulation. Four guests need substantial host memory. `--prepare-isolated-emulators` disables background Google apps only inside these disposable profiles; it is never applied to physical phones.

Build an installable, locally signed release with:

```powershell
python tools/package.py
```

The output is `artifacts/GhostProtocol.apk`. Preserve the ignored `.tools/signing/` directory for compatible updates. Version 2 intentionally rejects mixed-version rooms. The release contains no test runner and has not been published to an app store.

The packaged 2.0 release passed 49 core tests, nine native UI scenarios, three interaction regressions, and debug/release lint with no issues. Four Android emulators completed the full Bluetooth heist, recovered from an 18-second Bluetooth outage, and correctly ended without revival after a 75-second outage. All four devices agreed on the final state in each scenario. Four- and eight-node replication simulations also cover dropped commits and duplicate reward commands.

See `artifacts/verification.json` for the packaged APK checksum and evidence. Isolated warm navigation measured 69 ms against a 100 ms gate. The full software-rendered emulator frame profile includes jank and does not establish real-phone rendering performance. Physical four- and eight-phone radio behavior, printed-card camera scanning, gameplay balance, and learnability still need a room playtest when devices are available.
