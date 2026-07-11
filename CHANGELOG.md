# Changelog

Notable changes to CaptureBridge Android are recorded here. GitHub releases
remain the authoritative source for published APKs and their checksums.

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
