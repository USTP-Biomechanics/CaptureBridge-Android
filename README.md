# CaptureBridge Android

CaptureBridge is a local or USB-connected acquisition system for smartphone-based
markerless motion-analysis workflows. CaptureBridge Android is the phone-side
recording client: it discovers CaptureBridge Hub on a local Wi-Fi or LAN
network or connects through USB/ADB reverse forwarding, receives session names
and camera settings from the Hub, records video on the phone, streams live
preview when requested, and sends completed captures back to the Windows host.

The goal is repeatable acquisition, not a locked-in analysis algorithm. The app
stores organized phone videos and metadata that can be used downstream with
single-camera monocular analysis, multi-camera reconstruction, video-to-pose
workflows, mesh-based pose estimation, or OpenSim-compatible
inverse-kinematics pipelines.

## Companion Hub

CaptureBridge Android is the phone-side companion to
[USTP-Biomechanics/CaptureBridge-Hub](https://github.com/USTP-Biomechanics/CaptureBridge-Hub),
the Windows desktop controller and transfer host. For normal lab installs, the
Hub release ZIP is downloaded separately from the Android phone client. Install
`CaptureBridge-Android.apk` from the
[latest CaptureBridge Android release](https://github.com/USTP-Biomechanics/CaptureBridge-Android/releases/latest);
use this Android repository when building or modifying the phone client.

## Main Features

- UDP discovery of CaptureBridge Hub on the local network
- TCP control connection to the Hub on port `6000`, either through USB/ADB
  reverse forwarding or Wi-Fi/LAN discovery
- Battery percentage, charge status, and power-source updates for the Hub UI
- Centralized `NAME`, `START`, and `STOP` control from CaptureBridge Hub
- Scheduled `START_AT` and `STOP_AT` commands when Hub/phone time sync is
  available
- Android Camera2 camera capability reporting
- Remote camera setting requests for resolution, frame rate, ISO, and shutter
  time where supported by the phone
- Raw live preview streaming to the Hub over UDP `6101` or TCP through USB/ADB
  reverse forwarding
- Pre-armed H.264 MP4 recording through Android `MediaCodec`
- Rolling encoded video buffer with configurable preroll before the Hub's
  `START` command
- Start/stop trimming when finalizing captures, including phone-side command
  timing fields for downstream alignment checks
- Local capture storage before transfer
- Per-capture metadata files:
  - `.mp4` video
  - `.intrinsics.csv` frame-level camera metadata where available
  - `.state.json` capture, device, and camera settings metadata
  - `.segment.json` segment trimming and muxing metadata
  - `.camera_time.json` camera/encoder timing diagnostics when available
- Capture listing, selected-capture transfer, all-capture transfer, selected
  delete, and delete-all commands from the Hub
- Front/back camera switching when not recording

## Requirements

- Android device with camera hardware
- Android 7.0 / API 24 or newer on the device
- JDK 21 for command-line builds (the version used by GitHub Actions)
- For building from source: Android SDK Platform 36.1 and Android SDK
  Build-Tools 36.1.0
- CaptureBridge Hub running on a Windows computer
- USB debugging enabled and authorized for USB/ADB reverse use, or phone and
  Windows computer on the same private Wi-Fi or LAN
- For Wi-Fi/LAN use, local network access to TCP/UDP port `6000`
- For Wi-Fi live preview, outbound UDP from the phone to the Hub stream port,
  `6101` by default; for USB preview, the Hub requests TCP preview through ADB
  reverse forwarding

High frame rates depend on the phone. The app reports available modes through
Camera2, and the Hub should choose a mode supported by all connected phones.
The workflow has been used mostly with `1920 x 1080` recordings at up to
`240 fps` on supported devices.

## Build From Source

Open the project in Android Studio, or use the checked-in Gradle wrapper with
JDK 21. On Windows, configure `JAVA_HOME` if Java 21 is not already the active
JDK. Run the same unit-test, lint, and debug-build checks used by CI:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
```

The debug APK is written to:

```text
build/outputs/apk/debug/
```

To execute the privacy/manifest instrumentation tests, connect or start an
Android device, confirm it appears in `adb devices`, and run:

```powershell
.\gradlew.bat --no-daemon connectedDebugAndroidTest --console=plain
```

Release signing is optional for source verification but required for a
publishable APK. Provide `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and optionally
`ANDROID_KEY_PASSWORD` as environment variables or entries in the untracked
`local.properties` file. On Windows, the helper prompts for passwords without
writing them to the repository:

```powershell
.\scripts\test-release-signing.ps1 -KeystoreFile C:\path\to\capturebridge-release.jks -Alias key0
```

The signed release APK is written to `build/outputs/apk/release/`. Without a
release signing configuration, use `assembleDebug` for local installation and
verification instead of treating an unsigned release output as distributable.

### GitHub Tag Build

The GitHub Actions workflow in `.github/workflows/android-apk.yml` builds APKs
on `v*` tags or manual runs. When a tag is available, the workflow creates or
updates the matching GitHub Release and uploads `CaptureBridge-Android.apk`
plus `CaptureBridge-Android.apk.sha256`.

## Installation

For normal lab use, install `CaptureBridge-Android.apk` from the
[latest CaptureBridge Android release](https://github.com/USTP-Biomechanics/CaptureBridge-Android/releases/latest).
The CaptureBridge Hub portable release is distributed separately and does not
bundle the Android APK. If you build from this Android repository, use the
release APK produced by the normal Gradle or GitHub Actions release workflow.
Verify a downloaded release with its adjacent SHA-256 file before installing
it.

Install the chosen APK on each Android phone that should participate in a
CaptureBridge session. On first launch, Android will request camera permission.
The app also needs local network access for Wi-Fi discovery, or USB debugging
authorization when connecting through USB/ADB reverse.

Permissions used:

- `CAMERA`
- `INTERNET`

## Security and Data Handling

The app does not use a CaptureBridge-operated cloud service. Captures are
stored under the app's private internal storage and are transferred only after
the connected Hub requests them. Android backup is disabled so capture videos
and metadata are not eligible for Android cloud backup or device-transfer
backup. Captures remain on the phone until the operator deletes them from the
Hub controls or removes the app's data.

CaptureBridge control messages, live preview, and file transfers use local
TCP/UDP sockets without application-layer authentication or TLS encryption.
Use the system only through authorized USB/ADB connections or on a trusted,
private, access-controlled LAN. Do not expose the Hub ports to the public
Internet or an untrusted network.

Battery telemetry reports the current percentage, Android charge status, and
plugged source for the Hub UI. It is status telemetry, not a measurement of
current, voltage, power, energy consumption, temperature, or battery health.
The Hub does not persist these updates as a battery-measurement time series.

## Operator Workflow

1. Connect Android phones by USB with USB debugging enabled, or put the Windows
   host and all Android phones on the same private network.
2. Start CaptureBridge Hub on the Windows host.
3. Launch CaptureBridge Android on each phone.
4. Allow camera permission if prompted.
5. Wait for the app to try USB first and then discover/connect to the Hub over
   Wi-Fi if USB is unavailable.
6. Confirm the phones appear in the Hub.
7. Configure the capture name and camera settings in the Hub.
8. Let the Hub prepare/arm the phones when it sends the capture name or an
   explicit `PREPARE`/`ARM` command. While armed, the phone keeps a rolling
   encoded buffer so the capture can include a short preroll before `START`.
9. Press `START` in the Hub to mark the segment start on all connected phones.
   When clock samples are good enough, the Hub may send `START_AT` so the phone
   starts at a scheduled elapsed-time target.
10. Press `STOP` in the Hub to mark the segment end and finalize the trimmed
   MP4 file. When clock samples are good enough, the Hub may send `STOP_AT` so
   the phone stops at a scheduled elapsed-time target.
11. Transfer the current capture or all captures from the Hub.
12. Use the Hub's stream checkboxes when live phone preview is needed.

The Android screen shows connection state, recording state, the active capture
label, camera settings, and transfer state.

## Network Protocol Summary

The app first tries USB/ADB reverse connection to local TCP `6000`. If that is
not available, it searches for CaptureBridge Hub by broadcasting:

```text
DISCOVER_UDPCAMERA
```

The Hub replies with:

```text
UDPCAMERA_OK 6000
```

After connecting, the Android app sends:

```text
HELLO <device_name>
TRANSPORT <usb_adb_reverse|wifi|direct> host=<host>
BATTERY level_pct=<0..100> status=<status> plugged=<source>
```

The app sends `BATTERY` once after every connection and again only when the
normalized battery tuple changes. `status` is `charging`, `full`,
`discharging`, `not_charging`, or `unknown`; `source` is `usb`, `ac`,
`wireless`, `dock`, `none`, or `unknown`.

Supported Hub commands include:

- `NAME <capture_label>`
- `PING <payload>`
- `SYNC <seq> hub_tx_ns=<ns>`
- `PREPARE [<preroll_ms>|<json>]`
- `ARM [<preroll_ms>|<json>]`
- `START`
- `START_AT phone_elapsed_ns=<ns>`
- `STOP`
- `STOP_AT phone_elapsed_ns=<ns>`
- `LIST`
- `SETTINGS_LIST`
- `SETTINGS <json>`
- `GET <capture_name>`
- `GET_ALL`
- `DELETE <capture_name>`
- `DELETE_ALL`
- `LIVE_PREVIEW_START <json>`
- `LIVE_PREVIEW_STOP`

`LIVE_PREVIEW_START` includes `host`, `port`, `protocol`, `maxFps`,
`jpegQuality`, `maxDimension`, and optionally `streamKey`. The preview protocol
is `udp` for Wi-Fi/LAN and `tcp` when the Hub routes preview through USB/ADB
reverse forwarding.

Common Android responses include:

- `NAME_OK`
- `BATTERY level_pct=<0..100> status=<status> plugged=<source>`
- `PONG <payload> phone_elapsed_ns=<ns> phone_rx_ns=<ns> phone_tx_ns=<ns>`
- `SYNC_OK seq=<seq> hub_tx_ns=<ns> phone_rx_ns=<ns> phone_tx_ns=<ns>`
- `PREPARE_OK READY preroll_ms=<ms> camera_lead_ms=<ms>`
- `START_OK ROLLING_BUFFER ...`
- `STOP_MARKED ROLLING_BUFFER ...`
- `STOP_OK MEDIACODEC_MUXED ...`
- `READY PREVIEW` or `READY ARMED`
- `READY_ERR <reason>`
- `LIST_OK <json>`
- `SETTINGS_LIST_OK <json>`
- `SETTINGS_OK <json>`
- `SETTINGS_ERR <reason>`
- `TRANSFER_ACCEPTED <capture_name|ALL>`
- `TRANSFER_BEGIN <capture_name|ALL> <file_count> <total_bytes>`
- `FILE_BEGIN <relative_path> <size_bytes>`
- `FILE_DONE <relative_path>`
- `TRANSFER_DONE <capture_name> <file_count>`
- `TRANSFER_ALL_DONE <file_count> <total_bytes>`
- `TRANSFER_ERR <capture_name|ALL> <reason>`
- `DELETE_OK <capture_name|ALL>`
- `DELETE_ERR <capture_name|ALL> <reason>`
- `BUSY <reason>`
- `ERR_UNKNOWN <command>`
- `LIVE_PREVIEW_STATE <json|text>`

Responses to timing-sensitive commands include `phone_rx_ns` and `phone_tx_ns`.
The Hub uses those fields to summarize phone receive-to-transmit time and to
maintain background `SYNC` samples for scheduled `START_AT` / `STOP_AT`
commands.

## Capture Output

Captures are stored in the app's private `Captures` directory. A finalized
capture folder contains files named from the capture label, timestamp, and
device identifier. Typical files are:

```text
<capture_name>/<capture_name>.mp4
<capture_name>/<capture_name>.intrinsics.csv
<capture_name>/<capture_name>.state.json
<capture_name>/<capture_name>.segment.json
<capture_name>/<capture_name>.camera_time.json
```

The intrinsics CSV contains frame index, relative timestamp, camera matrix
fields where available, lens position, exposure time, ISO, and zoom. The state
JSON stores device, camera, resolution, frame-rate, shutter, ISO, manual-sensor
support, orientation, platform information, and the active timing/muxing
backend. The segment JSON records requested start/end timing, selected mux
frame offsets, keyframe selection, phone receive duration, and related trim
diagnostics. Camera-time diagnostics are written when the active backend has
camera/encoder timestamp information to report.

The current recording backend pre-arms a `MediaCodec` H.264 encoder before the
Hub starts a capture. Encoded samples are retained in a rolling in-memory buffer
and the final MP4 is muxed only after `STOP`, using the requested start and end
times. This lets the app keep the camera and encoder hot, include a configurable
preroll window before `START`, and cut the saved video to the commanded segment.
The state JSON also records the rolling-buffer backend, preroll duration,
requested and muxed presentation timestamps, camera lead compensation, and frame
selection policy.

## Synchronization Note

CaptureBridge coordinates acquisition by sending prepare, start, and stop
commands over USB/ADB reverse or a local WLAN/TCP connection. The Android app
pre-arms the camera pipeline and returns phone-side receive/transmit timing
fields on key command responses. The Hub also exchanges background `SYNC`
samples and can use `START_AT` / `STOP_AT` for scheduled phone-clock segment
marks when the sync estimate is usable. This helps inspect command timing and
trim the saved MP4 around the requested segment, but this version does not claim
measured frame-level synchronization. Studies that require frame-accurate timing
should validate timing with an external optical or electronic reference.

## Troubleshooting

### The phone does not connect

- Confirm CaptureBridge Hub is running.
- For USB, confirm USB debugging is enabled and authorized.
- For Wi-Fi, confirm phone and PC are on the same private Wi-Fi or LAN.
- For Wi-Fi, confirm the Windows firewall allows UDP `6000` and TCP `6000`.
- Disable VPNs, guest Wi-Fi isolation, or strict corporate network rules for
  testing.
- Press `Connect` in the Android app to retry discovery.

### The camera settings do not apply

- The requested resolution may not be supported by the phone.
- Manual ISO or shutter control depends on the phone's Camera2 capabilities.
- Stop recording before switching camera or changing settings.

### Live preview does not show in the Hub

- For Wi-Fi preview, confirm the Windows firewall allows inbound UDP `6101`.
- For Wi-Fi preview, confirm the phone and PC are still on the same private
  network.
- For USB preview, confirm the phone is connected through USB/ADB reverse.
- Reduce preview size or FPS in the Hub if the network is congested.

### Transfer is blocked

The app returns `BUSY` when it is recording, warming up or arming the camera, or
already transferring files. Stop the recording and wait for active transfers to
finish before requesting another transfer or delete action.

## Repository Layout

The repository keeps Android source under [src/](src/) for SoftwareX submission
compatibility. The root Gradle project is the Android application.

```text
src/main/java/com/marksimonlehner/capturebridge/
  MainActivity.kt              Android activity and Compose UI
  BatteryTelemetry.kt          Change-driven battery status reporting
  CaptureCommandHandler.kt     Hub command routing and protocol parsing
  TcpController.kt             UDP discovery, TCP connection, and file transfer
  CaptureCameraController.kt   Camera2 sessions, capture storage, metadata
  RollingVideoEncoder.kt       MediaCodec rolling buffer and MP4 segment muxing
  TimestampedCameraInput.kt    Timestamped camera frames for encoder input
  PhoneLivePreviewStreamer.kt  UDP/TCP JPEG live preview stream
src/main/AndroidManifest.xml
proguard-rules.pro
gradle/libs.versions.toml
build.gradle.kts
settings.gradle.kts
```

## Citation

If you use CaptureBridge in academic work, please cite the shared software
citation:

```text
Simonlehner, M. (2026). CaptureBridge: Hub and Android client [Computer software suite].
https://github.com/USTP-Biomechanics/CaptureBridge-Hub
https://github.com/USTP-Biomechanics/CaptureBridge-Android
```

The repository also includes the shared [CITATION.cff](CITATION.cff), which
GitHub uses for the `Cite this repository` button.

Release-to-release changes are summarized in [CHANGELOG.md](CHANGELOG.md).

## License

CaptureBridge Android is released under the MIT License. See
[LICENSE.txt](LICENSE.txt).
