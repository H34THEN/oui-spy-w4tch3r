# Persephone RF — Android head-unit edition

A small native Android app for local RF discovery and consent-based training. It lives beside the existing OUI-SPY Flutter companion and uses a separate package (`com.heathen.persephone`). The original companion/firmware are not dependencies of this APK.

## What works in this build (0.4)

- Android Wi-Fi **result listening** and BLE advertisement discovery without an ESP32.
- Optional **passive ESP32-S3 over direct USB CDC-ACM**, with bundled receive-only firmware. This adds received Wi-Fi beacons and client probe-request metadata, including devices that do not expose an AP.
- Cyberpunk HUD (cyan/yellow/magenta, monospace, grid and instrument styling), portrait/landscape layouts, large controls, Wi-Fi/BLE/watchlist filters, evidence detail, smoothed RSSI trace, and stale-signal indicators.
- Local JSONL recordings, complete CSV and GeoJSON exports, offline session replay, and a clearly labeled synthetic demo mode.
- Persistent Android folder selector: choose a writable SD-card folder, USB-storage folder, or another document provider. Completed sessions automatically copy there as JSONL, CSV, and GeoJSON. Private recovery copies remain if the card is missing/full.
- Receiver GPS coordinates only from fresh, accurate fixes. Mocked GPS is excluded from mapping. Wi-Fi samples older than five seconds do not receive GPS tags, and motion/timing uncertainty expands recorded accuracy. A native interactive map draws receiver observations and a route. A gated RSSI propagation fit can show a tentative search area; no measured transmitter position is claimed.
- Pwnagotchi-inspired companion expressions and observation epochs, without handshake collection, deauthentication, or password attacks.
- One-hour passcode unlock for discovery, storage, exports, settings, and overlays, with **Lock now**. Each connected training action still needs an owner/consent reference and explicit confirmation.
- A single connected training action: **GATT service UUID / characteristic-property inventory** of an explicitly approved, freshly observed BLE target. No characteristic reads or writes. Wi-Fi capability review is offline.

This is a compiled training preview, not a claim of radio-specific hardware qualification. Hardware scanning, BLE connection behavior, USB enumeration, SD mounting, and automotive power interruption still need the acceptance checks below on the actual radio. The Amazon product page could not be verified; the app's Diagnostics screen reports the installed Android version, resolution, permissions, and exposed features.

## Install and first run

Minimum Android: **8.0 / API 26**. Sideload the debug APK through the radio's file manager, or use:

```sh
adb install -r persephone-rf-debug.apk
```

1. Open **Persephone RF**. Initial passcode: **9818**.
2. System → Change passcode → approve with `9818` → enter a personal 6–12 digit passcode twice. Connected inventory and explicit Wi-Fi scan requests are blocked while the initial passcode is still in use.
3. Enable Wi-Fi, Bluetooth, and Android Location. Grant precise location and, on Android 12+, nearby Bluetooth permissions.
4. Logs → Choose save folder / SD card → select a writable **folder** on the card. Android 11+ can prohibit selecting storage roots; choose a subfolder such as `Persephone`.
5. HUD → Start discovery → approve. If permissions are requested, return and press Start again to approve the actual session. After a permission denial, the next approved session can run with the remaining capabilities. System has permission setup and diagnostics controls.
6. Stop & save finalizes the private recording and copies it to the selected folder. Leaving the app also stops/finalizes an app-only recording. An explicitly approved Maps overlay can instead continue Android discovery with a foreground notification. Selecting a folder does not resume scanning.

The debug signing key varies by build machine. An update built with a different key will not install over the existing APK. Export recordings before replacing an installation; production signing is intentionally not configured here.

## Discovery and detection limits

**Native Android discovery is not guaranteed RF-silent.** Listening-only mode consumes fresh system results/broadcasts; periodic standard scans require session approval. The OS or other apps may scan, and Android BLE scanning may send standard scan requests. The explicitly approved Wi-Fi button may transmit ordinary probe requests throughout the scan area. Android normally limits foreground requests to four scans per two minutes. Cached Wi-Fi results older than 30 seconds are discarded, and repeated platform result timestamps are not logged again.

An Android application cannot put an arbitrary head-unit chipset into monitor mode through these APIs, read all 802.11 frames, detect all client-only devices, or guarantee that a vendor's Bluetooth audio chip supports Android BLE. GPS and USB-host availability also vary. No raw PCAP, Remote ID decoder, classic Bluetooth/SDP inventory, firmware OTA, or OUI-SPY BLE command integration is claimed in this edition.

The ESP32 firmware is different: Wi-Fi `WIFI_MODE_NULL` + promiscuous management-frame receive, channels 1–11, and **passive** BLE scanning. It never starts an AP/station scan, associates, sends probes, advertises, connects to BLE targets, or injects frames. It collects only metadata, not traffic payloads or handshakes. Directed probe-request SSIDs are omitted. Wi-Fi security from this receiver is only `[OPEN]` / `[PRIVACY]`, not a complete WPA decoder. Up to four advertised BLE services and the manufacturer company ID are recorded; absent identifiers are not inferred.

Name, service, and manufacturer evidence are shown as **unverified signatures**, not proof of ownership/device type. The app uses a small explainable signature set rather than broadly labeling common chip vendors as surveillance. Spoofing, randomized addresses, radio coverage, hidden APs, changing firmware, and missing advertisements can create false positives or missed detections. Watchlist matching is case-insensitive substring matching over names, addresses, evidence, and advertised service UUIDs.

## Consent and passcode behavior

- Salted PBKDF2-HMAC-SHA256 (120,000 rounds); passcodes are not recorded in audit logs.
- Five failed attempts impose a persisted 60-second cooldown. A correct passcode unlocks the app for exactly one hour of elapsed time, including time spent in Maps. The lease persists across process restarts within the same boot; reboot, passcode change, or **Lock now** invalidates it. Discovery and overlay stop on expiry. Every connected action still requires an owner/consent reference and an explicit ownership/informed-consent checkbox.
- The exact target must be a connectable BLE address observed within 30 seconds, during a real native discovery session. Demo/replay/USB observations cannot start connected training.
- The approval audit must persist before radio actions run. Inventory closes after completion, failure, app exit, or 15 seconds. It never reads/writes characteristic values.
- App backup/device-transfer are excluded; the only exported manifest component is the launcher Activity; a temporary Wi-Fi system-broadcast listener reads only fresh results from WifiManager, never broadcast-supplied observations. There is no upload service. Internet permission exists solely for an explicitly approved street basemap; it is disabled by default and whenever the app leaves the foreground. The original upstream companion's robot exploitation controls and firmware commands are not reachable from this APK.
- The public initial PIN is a setup value, **not theft protection**. Change it. An app PIN cannot defend against root access, reflashing/uninstalling/clearing app data, extracting an unlocked SD card. SD exports are plaintext training data; private files use Android's app sandbox. UI screenshots are blocked on-device.
- Operators enter consent references themselves; the app does not independently verify a volunteer's identity or consent. Use connected training while parked, with a dedicated owned/volunteer device. A passenger can operate discovery during a driving demonstration.

## Build and test

Install JDK 17 or newer, Android SDK platform 35, and PlatformIO 6.1.18. Point `ANDROID_HOME` at the SDK, or create an ignored `local.properties` with `sdk.dir=...`.

```sh
cd headunit
./test-core.sh
./test-firmware.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
pio run -d receiver
```

The checked-in Gradle wrapper pins Gradle 8.9 with a distribution checksum. Production dependencies: **none**; native Android APIs only. Test dependencies: JUnit 4.13.2 / Robolectric 4.14.1. ESP32 platform/libraries are pinned in `receiver/platformio.ini`.

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/previews/landscape-0.png` through `landscape-4.png` (native Android 15 software renders, 960×480)
- `receiver/.pio/build/passive_xiao_s3/firmware.bin`
- `receiver/.pio/build/passive_s3_devkit/firmware.bin`

Checks include core policy/CSV helpers, persisted PIN changes and cooldown, foreground stop behavior, bounded device views with continued recording, demo connected-action blocking, USB telemetry validation, and an actual Android DocumentsProvider simulation that exports 600 observations and preserves the private source after a simulated full card. The beacon parser is fuzzed with 100,000 malformed frames under address/undefined-behavior sanitizers. Leak detection is disabled in that small host test because some sandbox runtimes use ptrace. Android layout/lifecycle tests cover API 28 and API 35; they do not simulate real RF hardware.

## ESP32 receiver setup

Supported compile targets:

| Target | Board | Connection |
|---|---|---|
| `passive_xiao_s3` | Seeed XIAO ESP32-S3 | Direct native USB CDC |
| `passive_s3_devkit` | ESP32-S3-DevKitC-1-N8 | **Native USB** port, not the UART bridge |

These are existing board profiles; no display/GPS/button pins are driven. The exact LilyGO pager model/revision is required before making its profile. Do **not** flash the generic DevKit profile onto an unidentified pager. Pager display/GPS/LoRa support is not included; ESP32 Wi-Fi/BLE cannot detect arbitrary sub-GHz signals.

For a matching XIAO only:

```sh
pio run -d receiver -e passive_xiao_s3 -t upload --upload-port /dev/ttyACM0
```

Replace the port with your actual board's port. PlatformIO uploads bootloader, partitions, and firmware at the proper offsets; `firmware.bin` alone is not a factory image. Then connect the board by a USB data/OTG cable to the head unit, approve a real discovery session, and select **Passive USB receiver…**. Grant Android's USB permission prompt. CP210x/CH340 UART bridges are not supported by this driver. ESP32-S3 direct CDC avoids an extra driver dependency, provided the radio exposes USB host.

The receiver starts with its radios idle. The app first sends `HELLO`; compatible firmware replies `{"persephone_passive":1}`. Only then does the approved app send `START`. `KEEP` refreshes the watchdog once per second. `STOP`, loss of USB DTR, or three seconds without a keepalive stops reception. Boot, disconnect, and app exit do not leave an autonomous scanner running. This handshake is compatibility identification, not cryptographic authentication; use only a receiver you control.

Firmware queue: 64 metadata events. USB/Android deduplicate observations to at most once per second per address; very dense RF environments can drop firmware events. This is awareness logging, not a lossless capture instrument. Private sessions cap at 32 MiB and stop below 10 MiB free storage; start another session after exporting. The live window/replay holds at most 512 device addresses; exports contain the full recorded event stream. Live list shows up to 100 rows by RSSI. Replay tolerates incomplete final JSONL lines after power loss. Normal recording flushes at least once per second when events arrive; sudden power loss may lose buffered events. A file provider that fails mid-export is cleaned up where possible, and the local source is retained.

## Radio acceptance before the presentation

1. Diagnostics: verify Android >=8, true Android BLE support, Location, GPS reception, writable SD subfolder, and direct USB CDC if using the receiver.
2. On an owned AP/BLE beacon, verify actual address/name/RSSI, movement trend, and stale flags after the transmitter stops. A GPS-tagged observation marks where the radio received it, not where the device resides.
3. Compare the same beacons with an independent trusted receiver. Check received client probes with an owned ESP32 test setup; native Android is expected to miss them.
4. Stop/restart the app; verify the prior recording, replay, exact JSONL/CSV counts, and SD exports. Remove/unmount the card before Stop and confirm the private session remains exportable.
5. Try wrong PINs, wait through cooldown, and confirm demo/replay cannot connect. With explicit consent, inventory an owned BLE beacon's services, then verify it disconnects. Deny/revoke Bluetooth permissions and confirm graceful failure.
6. Verify the ESP32 stops within three seconds when the app exits/USB disconnects. With an independent capture receiver, confirm the bundled ESP32 emits no Wi-Fi probes, deauth, association, or BLE scan requests. Ordinary Android discovery is a separate behavior.
7. Test the vehicle's ignition/power cycle and document-provider persistence. Rehearse Demo if the venue's RF/ROM behavior differs. Keep the screen operator parked or in the passenger seat.

## References and attribution

- [H34THEN/oui-spy-w4tch3r](https://github.com/H34THEN/oui-spy-w4tch3r): host repository and existing ESP32/Flutter architecture. This native edition does not send its engine-control protocol.
- [FlockSquawk](https://github.com/f1yaw4y/FlockSquawk): inspiration for metadata-based, explainable awareness. Its source was not bundled.
- [flock-you](https://github.com/colonelpanichacks/flock-you): provenance for the small name/service/manufacturer signature set (`Flock`, `Penguin-`, `FS Ext Battery`, `B4:1E:52`, accessory UUID, Raven services, XUNTONG `0x09C8`). These public identifiers are hypotheses, not validated device identities.
- [Pwnagotchi](https://github.com/evilsocket/pwnagotchi): inspiration for companion moods/epochs only; no code or offensive capture features imported.
- [Android Wi-Fi scanning](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [storage access framework](https://developer.android.com/training/data-storage/shared/documents-files).

## Priority surveillance tags and provenance

The default feed prioritizes detector candidates, then RSSI. **All** shows ordinary signals. Flock, Raven, Falcon/LPR, battery/accessory, and camera-vendor tags carry `confidence`, `ruleset`, `rule_ids`, and a readable evidence trail in JSONL/CSV exports and detail panels. Counts refer to candidates in the bounded live window, never confirmed installed cameras.

- **FLOCK**: bounded Flock-name patterns, direct `B4:1E:52` vendor evidence, and shared-prefix + received wildcard-probe evidence. Prefix + wildcard + ordered community IE fingerprint receives `CORROBORATED`, which still means an unverified candidate. The passive receiver checks the ordered TLVs/vendor bytes without retaining packet payloads.
- **RAVEN / AUDIO SENSOR**: bounded Raven-name hints or advertised custom services `3100`–`3500`. Two distinct custom services raise evidence to `MEDIUM`. Generic Device Information (`180A`), Health Thermometer (`1809`), Location/Navigation (`1819`), and Nordic UART **never** identify Raven by themselves. Aliases/duplicate service UUIDs do not increase confidence.
- **FALCON / LPR**: an explicit Falcon name is a **LOW** model hint. The referenced repositories do not prove a unique Falcon fingerprint; a generic Flock observation is not automatically labeled Falcon. Devices can use or spoof that name. A battery/OUI is not proof of an LPR camera.
- **Other cameras/LPRs**: bounded camera-vendor names (Hikvision, Dahua, Axis, Reolink, Wyze, Ring, Eufy), Pigvision/Condor/Sparrow hints, and explicit LPR/ALPR names are candidates. This cannot discover silent/cellular-only/wired cameras or universally classify every camera from RF.
- Broad community OUIs and XUNTONG `09C8` alone produce **SHARED HARDWARE / LOW**, below camera-family candidates. Common chips are not treated as proof of surveillance.

Reviewed public signature provenance (no third-party detector source copied):

| Repository | Reviewed revision | Contributions |
|---|---|---|
| [NSM-Barii/flock-back](https://github.com/NSM-Barii/flock-back) | `011e024e0fc0c06567f44ababfa7c957ac8b6b4e` | names, shared prefixes, custom Raven services; deliberately exclude generic service false positives |
| [Marslauncher/flock-you-scanner](https://github.com/Marslauncher/flock-you-scanner) | `fc02f45f6725ab9b9342a6edc7a4752289ba633b` | name and shared-hardware evidence |
| [yetisoldier/CYD-Flock-You](https://github.com/yetisoldier/CYD-Flock-You) | `6d68f748154799647bebff80c4af6edb211c4c73` | OUI facts, wildcard and ordered IE signature bytes, passive metadata approach |

Public identifier values and ordered IE facts are implemented in a new bounded matcher. Source claims are not independent field validation. Vendor product categories: [Flock Falcon/Raven descriptions](https://www.flocksafety.com/blog/product-announcement-q2-2023).

## Map interpretation and privacy

**MAP** works locally without street tiles. Cyan marks Flock-family candidates, magenta Raven, yellow Falcon/LPR, and muted blue ordinary/shared-hardware observations. Tap a receiver point to select a signal; drag, pinch, use ±, or Center. The selected strongest reception point is explicitly **STRONGEST RX**, with receiver accuracy, not a camera location. GeoJSON exports contain longitude/latitude receiver geometries and `position_role=receiver_observation`, with evidence/source properties. Synthetic recordings remain labeled `DEMO`.

A dashed orange **MODEL SEARCH AREA** is shown only for an eligible selected stationary-source hypothesis: at least eight distinct accurate fixes (≤25 m), at least 60 m of two-dimensional spatial coverage, variable RSSI, a bounded low-residual fit, and a 15-minute sample window. The algorithm fits an unknown RSSI intercept and a constrained path-loss exponent over candidate positions. Collinear routes, insufficient variation, mixed demo/real data, poor GPS, out-of-bounds/ambiguous fits, and mocked coordinates yield **origin unresolved**. The footprint is a model search region with a 25 m floor, **not** a confidence interval, bearing measurement, or triangulated transmitter coordinate. Reflections, buildings, moving transmitters, antennas, and RSSI calibration can invalidate the model. Controlled synthetic tests establish algorithm behavior, not real-world location accuracy.

**Enable street tiles…** explains privacy and requires the passcode. OSM receives the IP and requested viewport/tile area; no names, MACs, tags, or recordings are uploaded. Requests use HTTPS, an application User-Agent, visible attribution, and a 32 MiB HTTP cache honoring server headers. Only visible tiles are requested; there is no bulk/offline-area download. Disabled tiles and missing Internet leave the local overlay usable. Network enablement expires on foreground exit. [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/).

Use the Nothing Phone 3 for mobile GPS/radio testing; the HiBy R4 install/launch test is also supported. Outdoor precise-location fixes are needed for a meaningful receiver route. Installing/launching is not equivalent to live RF, map-source, SD, or external-receiver qualification. The currently connected Nothing device is Android 16 / API 36; the R4 is Android 12 / API 31. Both use the same app ID/debug signing key, so updates preserve the private passcode and recordings.


## Transparent overlay on Google Maps / other map apps

In **System**, allow **Display over other apps**, then **MAP → Overlay on Google / other Maps**. The external overlay now shows only a compact upper-left legend, colored dots, RSSI and receiver/phone-note coordinates. It has no embedded mini-map or main dashboard. Tap a dot for GPS/photo/nickname controls. PINGS also lists unlocated signals. A foreground notification and floating STOP control end it. Removing the task, Lock now, or one-hour expiry stops it; there is no boot start or automatic restart.

Enter two visible landmark coordinates ≥30 m apart, return to your map through Recents, drag A/B onto those landmarks ≥80 pixels apart, then LOCK. This aligns a **flat, static map snapshot**. There is no Google Maps/Waze viewport API. The overlay cannot follow another app's navigation camera, zoom, rotation, auto-centering, or 3D tilt. Outside touches pause dots; calibration expires after 30 seconds. For navigation, an integrated map must own its projection. RSSI controls the strength label, never an invented bearing or distance. Dots represent reception/phone-note positions, not verified cameras.

Saved-only mode starts no radios. **Continue Android discovery + GPS** starts a recorded foreground session. **Refresh Wi-Fi every 30 seconds** separately approves ordinary Android probe requests for that session, requires a personal passcode, and writes an audit before starting. Leaving Main preserves that approved service; USB and connected BLE training are not continued. Android may throttle requests while another map is foreground; rejected requests are reported rather than counted as fresh scans. Allow notifications for the persistent notification Stop control; the floating STOP button remains available if notifications are denied.

GPS/photo/nickname notes remain private and durable. GPS capture requires a non-mocked fix ≤10 seconds old and ≤100 m accuracy. JPEGs are resized ≤2048 pixels, EXIF orientation applied and metadata stripped; originals are unchanged. Eight photos per ping and a 128 MiB budget apply. Logs → Export ping notes + photos writes JSON, GeoJSON, and images to the selected SAF/SD folder, retaining private copies on failure.

## Automatic Wi-Fi refresh

Starting discovery now presents an approval checkbox for **every-30-second Wi-Fi refresh**, selected initially when a personal passcode is configured. The cadence is a request schedule, not a guarantee of a completed scan. Normal probes can be transmitted in the scan area. Approval and an audit cover the session; Stop, app-only foreground exit, Lock now, or unlock expiry cancel the timer. The Feed/HUD Wi-Fi control can stop refresh immediately or approve one scan / periodic scans. BLE scanning continues independently. No association, attacks, deauthentication, custom packet injection, or OS throttle bypass occurs.

The **Priority** feed hides ordinary APs; select **Wi-Fi** or **All**. Wi-Fi and Android Location must be enabled and precise location granted. Results older than 30 seconds remain excluded and repeated result timestamps are not logged twice. Android normally permits four foreground scans per two minutes, and imposes tighter background limits; other requests and OEM behavior can cause throttling even at a 30-second cadence. [Android scan limits](https://developer.android.com/develop/connectivity/wifi/wifi-scan).
