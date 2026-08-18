# Stage 6A2: Android USB coordinator bridge

This stage introduces the first application-scoped Android USB owner in A2.
It connects the Stage 5 pure behavior policies to Android `UsbManager`, while
leaving protocol transports and interface claiming for later stages.

## Ownership

`NekoFlashApplication` creates one `UsbSessionCoordinator` for the process.
`MainActivity` only forwards its launch/new Intent so Android's manifest USB
attach delivery can enter the coordinator. Activity recreation does not own
permission state, timers, detach handling, candidate selection, or mode-switch
polling.

The coordinator owns:

- startup enumeration scheduling (`350 ms`);
- Android descriptor mapping into the pure USB model;
- attach classification;
- package-scoped USB permission requests and the `30_000 ms` timeout;
- permission result rebinding through `UsbPermissionPolicy`;
- detach matching and pending-request removal;
- mode-switch polling (`16` attempts, `750 ms` interval);
- creation of `UsbSessionSnapshot` evidence after permission is available.

## Current boundary

A permitted descriptor candidate is recorded as the current USB generation and
a snapshot is written, but this stage does **not** open `UsbDeviceConnection`,
claim an interface, perform ADB/Fastboot handshakes, or call JNI/native USBFS.
The diagnostic event explicitly records `transport=not-opened` so hardware
evidence from this stage cannot be mistaken for protocol validation.

The next transport stage must consume the coordinator-owned candidate rather
than rediscovering USB from UI/ViewModels.

A2 hardware status remains **NOT YET VERIFIED** until a dedicated hardware run
is performed and its exported diagnostics are reviewed.
