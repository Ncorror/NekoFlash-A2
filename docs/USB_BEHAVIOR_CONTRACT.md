# USB behavior contract

This document pins the USB behavior that NekoFlash A2 must preserve while the
legacy ownership model is replaced by an application-scoped `UsbSessionCoordinator`.

Legacy baseline commit: `c49242c771dac9d147597c0d07e9ac1c6d320254`.

## Source-of-truth legacy files

- `UsbDeviceInspector.kt` - descriptor classification, candidate priority,
  re-enumeration filtering, and candidate rebinding.
- `MainActivity.kt` - attach intent handling, USB permission request/result,
  startup enumeration, detach handling, and mode-switch polling.
- `DeviceViewModel.kt` - serialized transport transitions and fail-closed shutdown.
- `UsbTransportShutdownPolicy.kt` - final close predicate.
- `UsbSessionSnapshot.kt` - descriptor evidence captured for diagnostics.
- `NativeUsbfsBackend.kt` / `native_usbfs.cpp` - native transfer lifetime,
  cancellation, drain, and poisoned-backend behavior.

The legacy files are behavioral evidence, not an ownership template for A2.

## Android platform integration boundary

The A2 master prompt explicitly permits controlled modernization of Android
framework integration while keeping the USB/device invariants below protected.
Receiver export mechanics, PendingIntent scoping, and UI-to-Application
lifecycle plumbing are implementation mechanisms, not protocol semantics.

Any modernization that can affect real USB delivery or re-enumeration remains
`NOT YET VERIFIED` until hardware evidence exists. It must not change candidate
selection, permission-result interpretation, mode-switch filtering, retry counts,
timing contracts, or transport/destructive semantics without the normal
behavior-change process.

## Entry authorization boundary

The approved A2 entry/file-access split keeps the legacy risk/session boundary
but removes broad storage access as a prerequisite for entering the application.

Required USB-facing behavior:

- a fresh process/full app entry starts with no volatile entry authorization;
- persisted acknowledgement of the current risk schema does not by itself enable
  USB processing;
- no startup scan, attach processing, USB permission request, auto-connect,
  reconnect, or mode-switch automation may begin before entry authorization;
- an attach delivered before authorization is treated only as a reason to show
  the entry gate. After authorization the original attach payload is not replayed;
  the coordinator uses the normal `350 ms` startup enumeration against the
  descriptors that are actually present then;
- Android may already have granted host access as part of a matching attach
  launch; that platform grant never substitutes for NekoFlash entry authorization
  and must not activate coordinator processing before the gate completes;
- configuration recreation may preserve the authorized session, while an
  ordinary non-configuration UI exit revokes it;
- until an explicit operation-owned lifecycle exists, ending that authorized UI
  entry also stops coordinator automatic callbacks/timers rather than leaving
  USB automation alive only because the process survived.

These rules do not make UI the USB owner. The gate decides whether the current
app entry is allowed to activate the Application-scoped coordinator; descriptor,
permission, endpoint, reconnect, and later transport state remain coordinator
responsibilities once activated.

Stage 6A3B wires this boundary into Android. The Application creates the
coordinator inactive; an authorized UI entry activates it, and ordinary
non-configuration exit deactivates it while no operation-owned lease exists. A
pre-authorization attach payload is consumed without descriptor processing and is
replaced by the normal `350 ms` startup enumeration after authorization.

Deactivation cancels startup and mode-switch callbacks plus permission timeouts,
clears pending permission/current descriptor state, and unregisters the dynamic
permission/detach receivers. These are lifecycle controls only; candidate and
permission-result policies are unchanged. Real-device behavior remains
`NOT YET VERIFIED`.

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

## Attachment identity and candidate rebinding

The stable candidate key contains Android `deviceName`, VID, PID, mode, and
interface index. The logical USB signature intentionally excludes Android
`deviceName`/`deviceId` and contains VID, PID, mode, interface class/subclass/
protocol, and selected endpoint addresses.

After a USB permission callback, a previously selected candidate may be rebound
to a fresh Android descriptor only when `deviceName` still matches. Rebinding
prefers the same interface index, then the same logical signature, then a unique
candidate with the same mode and match kind. If rebinding fails, the callback
descriptor is inspected again and its current primary candidate is used.

## Attach and startup enumeration behavior

Legacy `MainActivity` receives `USB_DEVICE_ATTACHED` as an Activity intent. The
same intent is marked consumed so Activity recreation does not process the same
attach payload twice. A consumed attach intent falls through to the normal
startup enumeration path instead of being treated as a second attach event.

A startup enumeration is scheduled once per legacy Activity instance after
`350 ms`. A2 represents that one-shot boundary as a coordinator-owned UI-entry
generation. Each `MainActivity.onCreate` re-arms only the startup-scan gate;
`onNewIntent` for the same Activity does not re-arm or start that fallback scan.
Destroying that Activity cancels only its still-pending startup callback, using
the UI-entry generation token so stale destruction cannot cancel a replacement
Activity's scan.

Permission registry, current USB generation, and mode-switch state remain owned
by the Application-scoped coordinator rather than being copied back into UI.
When the startup scan runs, automatic connection is allowed only while connection
state is `NONE` or `ERROR`, and only when discovery returns exactly one candidate.
Zero candidates and ambiguous multi-candidate results do not auto-connect. If a
new UI-entry startup scan finds a unique candidate while an older coordinator-
owned mode-switch watch is active, that startup candidate supersedes the watch
before permission is requested so the two schedulers cannot issue duplicate
connect attempts.

For a non-null attached device, legacy cancels any pending startup enumeration
and stops any active mode-switch watch before classifying the new descriptor.
This happens even if the descriptor is not recognized as ADB/Fastboot. A system
attach callback with no device does not cancel those schedulers and does not
request USB permission.

A2 must preserve the externally observable single-processing and conservative
auto-connect behavior without making an Activity the USB owner. The Android
adapter may replace the consumed-Intent implementation detail, but UI recreation
must not duplicate a physical attach event. A consumed attach payload falls back
to startup enumeration only when encountered during a new Activity creation,
matching the legacy create/new-intent distinction.

## Android receiver delivery

Permission callbacks and detach events use separate receiver responsibilities.

The permission receiver listens only to the app-owned package-specific USB
permission action. On API 33+ it is registered `RECEIVER_NOT_EXPORTED`. On API
26-32 A2 retains the pinned legacy dynamic-registration behavior behind a narrowly
scoped lint suppression; the permission PendingIntent itself remains package-
scoped. Tightening the pre-33 delivery mechanism further is a separate hardware-
sensitive platform migration.

The detach receiver listens only to `UsbManager.ACTION_USB_DEVICE_DETACHED`.
Because this is a protected Android system broadcast, the system-only receiver
uses the platform system-broadcast registration path without an exported/not-
exported flag. The two actions are not mixed in one filter because they have
different Android sender/export semantics.

## USB permission behavior

A permission request is tracked before checking `UsbManager.hasPermission`.
Pending requests are keyed by Android `deviceId`. If permission is already
granted, the just-added pending entry is removed and the selected candidate is
connected immediately without scheduling a permission timeout.

If permission is not already granted, the legacy flow requests it and starts a
`30_000 ms` timeout. Replacing a request for the same `deviceId` replaces that
pending value without changing the insertion order used by fallback lookup.

On a permission broadcast:

- the timeout keyed by the callback device's `deviceId` is cancelled first;
- denied permission removes a matching pending request when a device is present
  and never connects;
- a granted result without a device is an error and leaves pending state intact;
- pending lookup prefers exact `deviceId`, then the first pending request whose
  original `deviceName` equals the callback device name;
- a granted callback rebinds/re-inspects the callback descriptor before connect;
- if no pending request matches, a recognized callback device is treated as an
  automatic connection candidate.

The callback-id timeout rule is intentionally exact. When an OEM returns a fresh
object with a different `deviceId`, legacy can resolve the candidate by
`deviceName` while only cancelling the timeout keyed by the callback id. A2 must
not silently broaden that behavior without separate evidence and regression
coverage.

When a permission timeout fires, only the pending entry for that exact requested
`deviceId` is removed. If permission is still absent, the timeout is reported.
If permission became granted without the callback, the timeout error is
suppressed, but legacy does not implicitly connect from the timeout path.

## Detach and mode-switch behavior

A detach always removes pending permission state for the exact detached
`deviceId`.

The detached descriptor is considered the current device when either:

- `deviceName` equals the current device name; or
- `deviceId`, VID, and PID all equal the current device values.

An unrelated detach does not disconnect the current transport and does not start
re-enumeration tracking.

A detach matching the current device requests transport disconnect and starts a
mode-switch watch for both ADB and Fastboot sessions. The watch remembers the
previous logical signature and previous VID. It performs up to `16` scans, with
the first and each subsequent scan separated by `750 ms`.

A mode-switch scan rejects the previous logical signature and, when the previous
VID is known, rejects other vendors. Automatic reconnection occurs only when
exactly one changed candidate remains. A successful match ends the watch. A miss
consumes one attempt; after the sixteenth miss no further scan is scheduled.

## Transport shutdown

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

## A2 Stage 5 boundary

Stage 5A implements pure USB descriptors and candidate discovery/re-enumeration
selection. Stage 5B implements pure fail-closed shutdown predicates.

Stage 5C adds pure permission bookkeeping plus startup, attach, detach, and
mode-switch lifecycle decisions. It still does not register a receiver, create a
`PendingIntent`, call `UsbManager.requestPermission`, open a USB device, claim an
interface, perform a protocol handshake, or own JNI/native transfers.

The later `UsbSessionCoordinator` will be the sole owner of Android USB discovery,
permission, interfaces/endpoints, attach/detach, reconnect, and transport
lifetime. UI and feature ViewModels must not duplicate these rules.

A2 hardware status: **NOT YET VERIFIED**.
