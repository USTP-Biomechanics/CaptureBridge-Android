# Changelog

Notable changes to CaptureBridge Android are recorded here. GitHub releases
remain the authoritative source for published APKs and their checksums.

## 1.0.17 - 2026-10-13

- Fixed automatic high-speed exposure compensation, which never adjusted in
  1.0.16: in a constrained high-speed session the capture callbacks report the
  framework's inner capture session rather than the high-speed session the app
  keeps, so every result was ignored. Results are now matched to the current
  session by request tag.
- The automatic exposure compensation now changes only after auto exposure has
  settled: three consecutive checks must agree within 0.2 EV, and a new session
  waits 1.5 s before the first check. This stops it from hunting while auto
  exposure is still moving or the scene changes.

## 1.0.16 - 2026-10-13

- High-speed modes (above 60 fps), where Android forces auto exposure and fixes
  the exposure time near the frame period, now match the brightness of the
  requested ISO and shutter automatically: between recordings the phone sets
  the exposure compensation from the exposure time and ISO auto exposure
  chooses, within the camera's range, and remembers it per camera mode. A
  `highSpeedAeCompensationEv` in `SETTINGS` fixes the EV instead. `SETTINGS_OK`
  and `SETTINGS_LIST_OK` report `highSpeedAeCompensationMode`, the applied EV
  and the camera's `aeCompensation` range; each capture's `.state.json` records
  them with `exposure_control`. Non-finite ISO, shutter, or EV values are
  rejected with `SETTINGS_ERR INVALID_VALUES`.
- Phone live preview in high-speed modes now keeps the high-speed session and
  streams snapshots of its preview, so the Hub shows the real high-speed
  exposure and recording works while streaming.
- Changed the on-screen camera summary for high-speed modes to say the shutter
  is fixed and show the EV in use instead of ISO and shutter values that do not
  apply there.
- Added the app version under the "TCP camera recorder" subtitle.
- Improved USB-only operation without Wi-Fi: the phone probes the USB/ADB
  reverse tunnel every second, shows `No Wi-Fi - waiting for Hub over USB`
  instead of a Wi-Fi error, and handles a leftover tunnel with no Hub behind it
  (`USB tunnel open, Hub not answering - retrying`).
- Hardened Wi-Fi discovery: probes are sent to each target separately, subnet
  broadcast addresses go first, stray datagrams are ignored, and overlapping
  retries no longer race.
- Connection changes no longer rebuild the camera session (and discard the
  armed pre-roll) when phone live preview is not running.

## 1.0.15 - 2026-07-11

- Disabled Android cloud backup and device-transfer backup for locally stored
  capture videos and metadata.
- Removed the unused network-state permission and documented the local network
  security model and battery-telemetry limitations.
- Added push and pull-request CI for unit tests, lint, and debug builds.
- Replaced the template instrumentation test with manifest privacy checks.
- Documented reproducible JDK 21 build, test, lint, and release-signing commands.

## 1.0.14 - 2026-07-11

- Added change-driven battery status telemetry for the Hub UI.
- Added protocol and storage-safety unit tests, reconnect backoff, and capture
  path hardening.
- Added unit tests and lint verification to the signed APK release workflow.

## 1.0.13 - 2026-07-01

- Added TCP live-preview streaming for USB/ADB reverse connections.
- Added scheduled phone-clock capture boundaries for lag testing and improved
  transport reporting.

## 1.0.12 - 2026-06-27

- Added detailed camera, encoder, muxing, frame-gap, and timestamp diagnostics
  for normal and high-frame-rate captures.
