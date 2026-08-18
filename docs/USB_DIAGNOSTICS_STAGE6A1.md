# USB diagnostics - Stage 6A1

Baseline for this slice: `3bfda3f6bf7f730cfebf6b428a3f71ee5b775fcd`.

Stage 6A1 adds the diagnostic evidence format that will be used by the real
`UsbSessionCoordinator` before hardware validation. It does not enumerate USB,
request permission, open `UsbDeviceConnection`, claim interfaces, perform a
protocol handshake, or call JNI/native USBFS.

## Evidence captured per selected candidate

`UsbSessionSnapshot` records:

- unique session id and capture timestamp;
- ADB/Fastboot candidate mode and match kind;
- Android USB device id/name, VID/PID, and product name;
- every interface class/subclass/protocol;
- every endpoint address, direction, transfer type, and max packet size;
- selected interface index and selected bulk IN/OUT endpoint addresses;
- host Android SDK/release/manufacturer/model/device.

The machine-readable schema is
`io.github.ncorror.nekoflash.usb-session.v1`.

## Files

`UsbDiagnosticStore` writes into the app-specific diagnostics directory:

- `usb-events.txt` for compact one-line lifecycle events;
- `usb-session-<sessionId>.json` for machine-readable evidence;
- `usb-session-<sessionId>.txt` for human-readable evidence.

The store is fail-soft for diagnostics I/O: inability to write evidence is sent
to Android logcat and does not mutate USB/protocol behavior.

Explicit ZIP export/share is intentionally separate and must be implemented
before the first A2 hardware validation run so evidence can be collected without
relying on logcat or direct access to Android app-private storage.

A2 hardware status remains **NOT YET VERIFIED**.
