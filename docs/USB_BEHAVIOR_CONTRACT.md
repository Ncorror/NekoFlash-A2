# USB behavior contract

This document pins the USB behavior that NekoFlash A2 must preserve while the
legacy ownership model is replaced by an application-scoped `UsbSessionCoordinator`.

Legacy baseline commit: `c49242c771dac9d147597c0d07e9ac1c6d320254`.

## Source-of-truth legacy files

- `UsbDeviceInspector.kt` - descriptor classification, candidate priority,
  re-enumeration filtering, and candidate rebinding.
- `MainActivity.kt` - USB permission request/result and detach orchestration.
- `DeviceViewModel.kt` - serialized transport transitions and fail-closed shutdown.
- `UsbTransportShutdownPolicy.kt` - final close predicate.
- `UsbSessionSnapshot.kt` - descriptor evidence captured for diagnostics.
- `NativeUsbfsBackend.kt` / `native_usbfs.cpp` - native transfer lifetime,
  cancellation, drain, and poisoned-backend behavior.

The legacy files are behavioral evidence, not an ownership template for A2.

## Descriptor discovery

Every ADB/Fastboot candidate requires a bulk IN endpoint and a bulk OUT endpoint.
When several endpoints match, the first bulk endpoint in each direction is used.

Candidate priority on one USB device is:

1. canonical ADB: vendor-specific class, subclass `0x42`, protocol `0x01`;
2. canonical Fastboot: vendor-specific class, subclass `0x42`, protocol `0x03`;
3. Android-compatible Fastboot: vendor-specific class, subclass `0x42`, any
   protocol other than `0x01` and `0x03`;
4. generic vendor Fastboot fallback: vendor-specific class and protocol other
   than ADB `0x01`.

If any canonical ADB interface exists, neighboring Fastboot-like vendor
interfaces are ignored. Otherwise canonical Fastboot wins over compatible and
generic matches. A generic descriptor match is only a candidate; Fastboot
handshake must still confirm the peer.

## Attachment identity and re-enumeration

The stable candidate key contains Android `deviceName`, VID, PID, mode, and
interface index. The logical USB signature intentionally excludes Android
`deviceName`/`deviceId` and contains VID, PID, mode, interface class/subclass/
protocol, and selected endpoint addresses.

A mode-switch scan ignores the previous logical signature. When the previous
vendor is known, the scan keeps the same VID. Automatic mode-switch selection is
performed only when exactly one changed candidate remains; ambiguity is not
resolved by guessing.

After a USB permission callback, a previously selected candidate may be rebound
to a fresh Android descriptor only when `deviceName` still matches. Rebinding
prefers the same interface index, then the same logical signature, then a unique
candidate with the same mode and match kind.

## Permission behavior to preserve in the Android adapter

The current legacy flow requests USB permission only when `UsbManager` does not
already report permission. Pending requests are keyed by Android `deviceId`,
with a `deviceName` fallback when the permission broadcast returns a fresh device
object. The permission callback descriptor is re-inspected/rebound before the
transport is opened.

A denied permission result clears the pending request. A request with no response
is treated as timed out after 30 seconds. These framework rules are documented
here but are intentionally not implemented in the pure Stage 5A selector.

## Detach and transport shutdown

Transport generations are serialized. Duplicate connect requests for the same
stable candidate are ignored while that candidate is connecting or connected.
A new generation first shuts down the previous transport before publishing the
new one.

USB close is fail-closed around Native USBFS:

- an unfinished operation requires cancellation/drain even if a coroutine is
  already cancelling;
- either Java/Kotlin-side active-transfer state or native active-transfer state
  requires drain;
- `UsbDeviceConnection` may close only when both transfer views are idle;
- if drain cannot be confirmed within the legacy shutdown timeout, the current
  connection is not closed and new connections are blocked until full app
  restart;
- a native backend poisoned by an unproven URB drain must not accept another
  native transfer in the same process.

Cancellation is asynchronous: a native transfer remains active until the
blocking native call returns after its DISCARDURB/REAP cleanup path.

## Hardware evidence inherited from legacy

The legacy hardware campaign demonstrated real ADB/Fastboot re-enumeration on
POCO X3 Pro (`vayu`) and POCO X7 Pro (`rodin`), real Native USBFS Fastboot
payloads, Recovery/Sideload transitions, and return to normal ADB. Those results
are evidence for behavior that A2 must preserve; they do not validate the new A2
implementation.

## A2 Stage 5A boundary

Stage 5A pins the full legacy USB contract in this document and implements only
pure descriptors plus candidate selection/re-enumeration policy and regression
tests. It does not open a USB device, request permission, claim interfaces,
perform protocol handshakes, or own JNI/native transfers.

Stage 5B will add the pure fail-closed shutdown predicates before Android USB
ownership is introduced.

The later `UsbSessionCoordinator` will be the sole owner of Android USB discovery,
permission, interfaces/endpoints, attach/detach, reconnect, and transport
lifetime. UI and feature ViewModels must not duplicate these rules.

A2 hardware status: **NOT YET VERIFIED**.
