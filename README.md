# BLE Proximity — Kotlin Multiplatform

Turns the Bluetooth signal strength every phone already reports into a steady distance
estimate and a near, mid or far zone, in shared Kotlin that runs on Android today and on
iOS without changes.

Apps that react to people being close by, such as a check-in screen that lights up at the
desk or a reminder when a tagged bag is left behind, need to know roughly how far away a
device is. Bluetooth Low Energy (BLE) signal strength, or RSSI, is the cheapest signal for
that, but it jumps by several decibels from one packet to the next, so a raw readout swings
between 1 m and 3 m while the phone lies still. This project makes the estimate usable by
smoothing the signal, converting it with a radio path-loss model, reporting the uncertainty
as a range, and adding hysteresis at zone boundaries so the zone holds steady.

| Part | What it does |
| --- | --- |
| `shared/` (Kotlin Multiplatform) | RSSI smoothing (moving average and Kalman filter), path-loss distance model, zones with hysteresis, per-device tracking, the `BleScanner` interface. Tested in `commonTest` on the JVM and the iOS simulator. |
| `androidApp/` | BLE scanning, runtime permissions for every Android version from 8.0, a Compose screen with live distance and zone, lifecycle-aware scanning. |

## Why RSSI distance estimates are noisy

RSSI measures received power, and much besides distance changes received power.

- **Multipath fading.** Walls, floors and furniture reflect the signal, and the copies add
  up or cancel at the receiver. BLE's wavelength is 12 cm, so moving a phone a few
  centimetres can change RSSI by 10 dB.
- **Three channels.** A device advertises on 2402, 2426 and 2480 MHz in turn. Each
  frequency fades differently, and Android does not say which channel a packet arrived on.
- **Bodies.** The human body is mostly water, which absorbs 2.4 GHz. A phone in a back
  pocket reads weaker than the same phone in a hand at the same distance.
- **Hardware.** Transmit power, antenna design and receiver calibration differ between
  models. The usual "−59 dBm at 1 m" is a starting assumption, not a property of a device.
- **Environment.** The path-loss exponent *n* is 2 in open space and 2.5 to 3.5 indoors.

The log-distance model turns these decibel errors into distance *ratios*:
d = 10<sup>(P₁ − RSSI) / 10n</sup>, so an error of *e* dB multiplies the distance by
10<sup>e / 10n</sup>.

| RSSI error | Distance range in open space (n = 2) | Indoors (n = 3) |
| --- | --- | --- |
| ±2 dB | ×0.79 to ×1.26 | ×0.86 to ×1.17 |
| ±4 dB | ×0.63 to ×1.58 | ×0.74 to ×1.36 |
| ±6 dB | ×0.50 to ×2.00 | ×0.63 to ×1.58 |

A typical 4 dB scatter therefore makes a device at 2 m read anywhere from 1.3 m to 3.2 m.
The app handles this in three ways:

1. **Smoothing, with a choice of two filters.** On simulated 4 dB noise at ten packets a
   second:

   | Filter | Scatter left | Packets to follow a real 10 dB move to within 1 dB |
   | --- | --- | --- |
   | Moving average, last 8 packets | 1.46 dB | 8 |
   | Kalman | 1.20 dB | 14 |

   Neither wins outright: more smoothing means slower response, and the Kalman settings
   set where that trade lands. The Kalman filter's real advantage is time. It charges
   drift per second, not per packet, so after a five-second silence the next packet moves
   the estimate four times as far as usual. A moving average weighs a packet from ten
   seconds ago the same as one from 100 ms ago, and BLE packets arrive irregularly.
2. **A range, not just a number.** The screen shows the distance consistent with the
   smoothed RSSI ±3 dB, so the user sees how much to trust it.
3. **Calibration.** Holding a device 1 m away and tapping *calibrate* replaces the
   −59 dBm default with that device's own one-metre reading. The advertised TX power can
   stand in instead (it is the radio's output minus 40.2 dB of free-space loss over the
   first metre at 2.44 GHz), but it leaves out antenna and enclosure losses, so it is off
   by default.

## What the zones are for

Apps make decisions, not measurements: show the check-in button when a visitor is near,
warn when a tag goes far. Zones turn a wobbling number into that decision. The defaults
are near up to 1 m, mid up to 3 m, and far beyond.

Hysteresis keeps the decision steady at a boundary. Each boundary is a band: to leave
*near* the distance must pass 1.25 m, and to come back it must drop below 0.8 m. Inside
the band the previous zone holds. The band is a ratio rather than a fixed number of metres
because RSSI noise is multiplicative in distance; a ratio of 1.25 is about ±2 dB.

A test places a simulated device exactly on the 1 m boundary with 4 dB noise and Kalman
smoothing. Over 1000 packets the zone changes 202 times without hysteresis and 11 times
with it.

## The scanner boundary

Shared code sees Bluetooth only through one interface:

```kotlin
interface BleScanner {
    fun availability(): Flow<ScannerAvailability>   // Ready, BluetoothOff, LocationServicesOff, Unsupported
    fun advertisements(): Flow<Advertisement>       // cold: collecting starts the scan, cancelling stops it
}
```

`AndroidBleScanner` implements it on `BluetoothLeScanner`, and it is the only class that
imports `android.bluetooth`. Everything after it, from smoothing to zones, is common code
driven by `Flow<Advertisement>.toProximityReadings(config, clock)`. Because the scan is a
cold flow, its lifetime is the collector's lifetime: a scan cannot outlive the screen.

**Why a hand-written scanner rather than Kable.** Kable is a solid Kotlin Multiplatform
BLE library, and its strength is connections: GATT service discovery, reading and writing
characteristics, and connection state on Android, iOS and the web. This app only scans,
and proximity work depends on details of the Android scan itself: the scan mode, the raw
`ScanResult` with its TX power and nanosecond timestamp, the failure codes, and Android's
limit on how often a scan may start. Implementing the interface directly keeps all of that
in one class of about 130 lines that a reviewer can read, with no extra dependency. The interface keeps
the choice open: an iOS scanner on CoreBluetooth, or Kable on both platforms once the app
needs connections, plugs in without touching the signal code.

## Android behaviour

**Permissions** follow the Android version:

| Android | Runtime permissions | Also needed |
| --- | --- | --- |
| 12 and later (API 31+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | — |
| 8.0 to 11 (API 26–30) | `ACCESS_FINE_LOCATION` | Location services switched on |

`BLUETOOTH_SCAN` carries `neverForLocation`, since the app measures distance to devices,
never the user's position. That spares Android 12+ users a location prompt; the cost, as
Android documents, is that some beacon formats are filtered out of such scans. An app
built around iBeacons would drop the flag and request fine location on every version.
`BLUETOOTH_CONNECT` lets the app ask the user to switch Bluetooth on. If the user refuses
twice, Android stops showing the dialog, so the screen offers the app's settings page
instead. Permissions are re-read on every resume.

**Background.** The scan runs only while the screen is visible. The Compose screen
collects state with `collectAsStateWithLifecycle`, and the ViewModel keeps its flows alive
for five seconds after the last collector leaves (`WhileSubscribed(5_000)`). A rotation
therefore keeps the scan running, and leaving the app stops it within about five seconds;
coming back starts it again. This suits a live distance screen: low-latency scanning
drains the battery, and Android pauses unfiltered scans when the screen turns off anyway.
Proximity alerts in the background would need a foreground service of type
`connectedDevice` with filtered scans, a separate decision with its own notification and
battery budget.

**Other device behaviour the app handles:**

- Android 7+ allows five scan starts per 30 seconds and silently returns nothing after
  that. `ScanStartLimiter` (shared, tested) delays a start that would break the limit.
- Bluetooth or location switched off mid-scan: a broadcast receiver updates availability,
  the scan stops, and the screen asks for the setting.
- Android 11 and lower need location services on for scan results, not just the
  permission.
- Calibrating a device or switching the smoothing method updates the running tracker in
  place, so neither restarts the scan.

## Running it

```bash
./gradlew :shared:allTests            # shared tests on the JVM and the iOS simulator
./gradlew :androidApp:installDebug    # needs a real phone: the emulator has no real Bluetooth radio
```

Requires JDK 17+, the Android SDK (API 36), and Xcode for the iOS test target. CI runs the
tests, the debug build and Android lint on every push.

## How it was built with Claude Code

Shem built this repository with Claude Code, one session, in small steps that each ended
in a check. The agent wrote the code; Shem set the order of work, the rules in the context
file, and the standard each step had to meet.

**Breaking the task down.** The work ran from the most testable parts outwards, so each
step stood on checked ground:

1. Write the context file and check the toolchain (JDK, Android SDK, Xcode, which Gradle
   and Kotlin versions were already cached), so the build pinned versions known to work.
2. Signal maths in `commonMain` with its tests: filters, path-loss model, zones.
3. The per-device tracker and the Flow pipeline, tested with virtual time.
4. The Android side: scanner, permissions, ViewModel, Compose screen.
5. CI, lint and this README.

**What went in the context file.** The context file, `CLAUDE.md`, is what Claude Code
reads at the start of every session. It holds the decisions an agent would otherwise
re-derive, or get wrong, each time: the module layout and the one class allowed to touch
Bluetooth; that `commonMain` takes time as a parameter and stays platform-free; the units
(dBm, metres, monotonic milliseconds); that a scan is a cold flow and runs only while the
UI is visible; the exact permission set; the commands; and a definition of done that
includes a real-phone check.

**How the agent's output was checked.**

- **Expectations by hand.** Every numeric test states its arithmetic in a comment and was
  worked out from the formula, not copied from the code's output. The Kalman gain test
  derives the steady state, 0.1618, from the filter's Riccati equation.
- **Tests that can fail.** Eight deliberate bugs were planted in the shared code one at a
  time: hysteresis removed on either side, the Kalman filter ignoring time or never
  gaining certainty, an off-by-one in dropping silent devices, filters kept across a
  smoothing change, the scan limiter allowing a sixth start, and a wrong factor in the
  path-loss formula. The suite caught every one, each in the tests written for it.
- **Numbers measured, then written.** The filter figures in this README come from running
  the simulation, and running it corrected a claim: the first draft said the Kalman filter
  follows a real move faster than the moving average. It is slower (14 packets against 8),
  so the filter gained the time-aware drift that gives it a real edge, and the text now
  states the trade-off.
- **Platform claims against the source.** The permission set and the `neverForLocation`
  beacon caveat were checked against Android's Bluetooth permissions guide; the API level
  of `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (33) against the SDK's API database.
- **Design review, not just code review.** Three of the agent's first choices changed on
  review: the first pipeline rebuilt the tracker on every settings change, which would have
  restarted the scan and run into Android's start limit, so settings now flow into the
  running tracker; the advertised TX power was the default calibration until the antenna
  losses it ignores made it opt-in; and the shared module moved to the new
  `com.android.kotlin.multiplatform.library` plugin after the build warned that the old
  pairing ends with Android Gradle Plugin 9.
- **Every step built and ran.** 50 shared tests on the JVM and the iOS simulator, the
  debug APK, and Android lint with no findings.
- **The app on a device.** On an Android 17 emulator: the permission prompt, the scan
  starting, the scan stopping five seconds after the app left the screen and restarting
  on return (read from the Bluetooth stack's own log), and Bluetooth switched off mid-scan
  showing its card and the system's turn-on dialog.
