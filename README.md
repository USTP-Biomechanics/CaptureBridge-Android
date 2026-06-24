# CaptureBridge Android

CaptureBridge is a local-network acquisition system for smartphone-based
markerless motion-analysis workflows. CaptureBridge Android is the phone-side
recording client: it discovers CaptureBridge Hub on a local Wi-Fi or LAN
network, receives session names and camera settings from the Hub, records video
on the phone, streams live preview when requested, and sends completed captures
back to the Windows host.

The goal is repeatable acquisition, not a locked-in analysis algorithm. The app
stores organized phone videos and metadata that can be used downstream with
single-camera monocular analysis, multi-camera reconstruction, video-to-pose
workflows, mesh-based pose estimation, or OpenSim-compatible
inverse-kinematics pipelines.

## Companion Hub

CaptureBridge Android is the phone-side companion to
[USTP-Biomechanics/CaptureBridge-Hub](https://github.com/USTP-Biomechanics/CaptureBridge-Hub),
the Windows desktop controller and transfer host. For normal lab installs, the
Hub release ZIP includes a ready-to-install `app-release.apk`; use this Android
repository when building or modifying the phone client.

## Main Features

- UDP discovery of CaptureBridge Hub on the local network
- TCP control connection to the Hub on port `6000`
- Centralized `NAME`, `START`, and `STOP` control from CaptureBridge Hub
- Android Camera2 camera capability reporting
- Remote camera setting requests for resolution, frame rate, ISO, and shutter
  time where supported by the phone
- Raw live preview streaming to the Hub over UDP `6101`
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
- Capture listing, selected-capture transfer, all-capture transfer, selected
  delete, and delete-all commands from the Hub
- Front/back camera switching when not recording

## Requirements

- Android device with camera hardware
- Android 7.0 / API 24 or newer on the device
- For building from source: Android SDK Platform 36.1 and Android SDK
  Build-Tools 36.1.0
- CaptureBridge Hub running on a Windows computer
- Phone and Windows computer on the same private Wi-Fi or LAN
- Local network access to TCP/UDP port `6000`
- For live preview, outbound UDP from the phone to the Hub stream port, `6101`
  by default

High frame rates depend on the phone. The app reports available modes through
Camera2, and the Hub should choose a mode supported by all connected phones.
The workflow has been used mostly with `1920 x 1080` recordings at up to
`240 fps` on supported devices.

## Build From Source

Open the project in Android Studio, or build the signed APK from the command line:

```powershell
.\gradlew.bat assembleRelease
```

The release APK is written to:

```text
build/outputs/apk/release/
```

Release builds require the repository signing configuration used by the normal
Android Gradle workflow.

### GitHub Tag Build

The GitHub Actions workflow in `.github/workflows/android-apk.yml` builds APKs
on `v*` tags or manual runs. When a tag is available, the workflow creates or
updates the matching GitHub Release and uploads `CaptureBridge-Android.apk`.

## Installation

For normal lab use, install the ready-to-install `app-release.apk` bundled in
the CaptureBridge Hub portable release. If you build from this Android
repository, use the release APK produced by the normal Gradle or GitHub Actions
release workflow.

Install the chosen APK on each Android phone that should participate in a
CaptureBridge session. On first launch, Android will request camera permission.
The app also needs local network access so it can discover and connect to the
Hub.

Permissions used:

- `CAMERA`
- `INTERNET`
- `ACCESS_NETWORK_STATE`

The app does not use a cloud service. Captures are stored in the app's local
private storage and are transferred only when the Hub sends a transfer command.

## Operator Workflow

1. Put the Windows host and all Android phones on the same private network.
2. Start CaptureBridge Hub on the Windows host.
3. Launch CaptureBridge Android on each phone.
4. Allow camera permission if prompted.
5. Wait for the app to discover and connect to the Hub.
6. Confirm the phones appear in the Hub.
7. Configure the capture name and camera settings in the Hub.
8. Let the Hub prepare/arm the phones when it sends the capture name or an
   explicit `PREPARE`/`ARM` command. While armed, the phone keeps a rolling
   encoded buffer so the capture can include a short preroll before `START`.
9. Press `START` in the Hub to mark the segment start on all connected phones.
10. Press `STOP` in the Hub to mark the segment end and finalize the trimmed
   MP4 file.
11. Transfer the current capture or all captures from the Hub.
12. Use the Hub's stream checkboxes when live phone preview is needed.

The Android screen shows connection state, recording state, the active capture
label, camera settings, and transfer state.

## Network Protocol Summary

The app searches for CaptureBridge Hub by broadcasting:

```text
DISCOVER_UDPCAMERA
```

The Hub replies with:

```text
UDPCAMERA_OK 6000
```

After discovery, the Android app opens a TCP connection to the Hub and sends:

```text
HELLO <device_name>
```

Supported Hub commands include:

- `NAME <capture_label>`
- `PREPARE [<preroll_ms>|<json>]`
- `ARM [<preroll_ms>|<json>]`
- `START`
- `STOP`
- `LIST`
- `SETTINGS_LIST`
- `SETTINGS <json>`
- `GET <capture_name>`
- `GET_ALL`
- `DELETE <capture_name>`
- `DELETE_ALL`
- `LIVE_PREVIEW_START <json>`
- `LIVE_PREVIEW_STOP`

Common Android responses include:

- `NAME_OK`
- `PREPARE_OK READY preroll_ms=<ms> camera_lead_ms=<ms>`
- `START_OK`
- `STOP_OK`
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

## Capture Output

Captures are stored in the app's private `Captures` directory. A finalized
capture folder contains files named from the capture label, timestamp, and
device identifier. Typical files are:

```text
<capture_name>/<capture_name>.mp4
<capture_name>/<capture_name>.intrinsics.csv
<capture_name>/<capture_name>.state.json
```

The intrinsics CSV contains frame index, relative timestamp, camera matrix
fields where available, lens position, exposure time, ISO, and zoom. The state
JSON stores device, camera, resolution, frame-rate, shutter, ISO, manual-sensor
support, orientation, and platform information.

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
commands over a local WLAN/TCP connection. The Android app pre-arms the camera
pipeline and returns phone-side receive/transmit timing fields on key command
responses, which helps inspect command timing and trim the saved MP4 around the
requested segment. This is useful for near-simultaneous recording and structured
multi-device acquisition, but this version does not claim measured frame-level
synchronization. Studies that require frame-accurate timing should validate
timing with an external optical or electronic reference.

## Troubleshooting

### The phone does not connect

- Confirm CaptureBridge Hub is running.
- Confirm phone and PC are on the same private Wi-Fi or LAN.
- Confirm the Windows firewall allows UDP `6000` and TCP `6000`.
- Disable VPNs, guest Wi-Fi isolation, or strict corporate network rules for
  testing.
- Press `Connect` in the Android app to retry discovery.

### The camera settings do not apply

- The requested resolution may not be supported by the phone.
- Manual ISO or shutter control depends on the phone's Camera2 capabilities.
- Stop recording before switching camera or changing settings.

### Live preview does not show in the Hub

- Confirm the Windows firewall allows inbound UDP `6101`.
- Confirm the phone and PC are still on the same private network.
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
  MainActivity.kt              Android UI and Hub command handling
  TcpController.kt             UDP discovery, TCP connection, and file transfer
  CaptureCameraController.kt   Camera2 sessions, capture storage, metadata
  RollingVideoEncoder.kt       MediaCodec rolling buffer and MP4 segment muxing
  TimestampedCameraInput.kt    Timestamped camera frames for encoder input
  PhoneLivePreviewStreamer.kt  UDP JPEG live preview stream
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

## License

CaptureBridge Android is released under the MIT License. See
[LICENSE.txt](LICENSE.txt).
