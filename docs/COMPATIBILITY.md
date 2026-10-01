# Compatibility and diagnostics

## Information to collect

- Vehicle brand, model, year, market and trim.
- Head-unit menu names and complete software version.
- Head-unit part number or label.
- Phone model and Android version.
- ELM327 model, connection type and firmware.
- Sanitized CarLife connection and OBD logs.

## CarPlay

An Android phone cannot reliably identify itself as an Apple CarPlay device. CarPlay uses Apple-managed authentication and accessory requirements. This project does not attempt to bypass them.

## Baidu CarLife

Compatibility depends on the head-unit firmware and transport, not only the vehicle year or engine code. Verify the official CarLife client first, then record the actual local ports and handshake used by the target head unit.

## Bluetooth

The original audio and MediaSession baseline is retained for phones and head units that support A2DP and AVRCP control.

## Diagnostics

The application records:

- USB device and accessory descriptors.
- CarLife local listener connections.
- Sanitized command lengths and directions.
- OBD command responses in debug logs.

Before publishing logs, remove VIN, plate, location, WiFi credentials, public IPs, accounts and tokens.
