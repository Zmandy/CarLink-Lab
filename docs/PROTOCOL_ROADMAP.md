# Protocol roadmap

## Current state

- The tested Tucson head unit accepts the six local CarLife TCP channels.
- Initial handshake and H.264 video display were confirmed.
- TPMS and fuel are read through WiFi ELM327.
- The dashboard is rendered by WebView from an HTML asset.

## Next work

1. Move vehicle-specific constants into a profile object.
2. Add an adapter capability probe for available OBD PIDs.
3. Add a raw protocol logger with explicit opt-in and automatic redaction.
4. Separate transport, session state and dashboard rendering.
5. Add automated parser tests using captured and sanitized byte arrays.
6. Add touch-event handling only after the event format is confirmed.
7. Document head-unit firmware and port differences per vehicle.
8. Replace the third-party fuel price endpoint with a configurable provider.

## Not planned

- Apple CarPlay identity spoofing.
- MFi authentication bypass.
- Firmware modification.
- Blind writes to unknown CAN addresses.
- Unattended testing while driving.
