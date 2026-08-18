# Stage 6A2: Android USB coordinator bridge

This stage introduces the first application-scoped Android USB owner in A2.
It connects the Stage 5 pure behavior policies to Android `UsbManager`, while
leaving protocol transports and interface claiming for later stages.

## Ownership

`NekoFlashApplication` creates one `UsbSessionCoordinator` for the process.
`MainActivity` only reports Activity creation/destruction or forwards a new
Intent so Android's manifest USB attach delivery can enter the coordinator.
Activity creation starts a new coordinator-owned UI-entry generation and re-arms
the legacy startup-scan one-shot gate; `onNewIntent` within the same Activity
does not re-arm or start that fallback scan. Activity destruction can cancel
only the still-pending startup callback belonging to that exact generation.

Permission state, current USB generation, detach handling, candidate selection,
and mode-switch polling remain Application-scoped. They are not copied back
into Activity simply to reproduce accidental legacy UI-lifetime state loss.

The coordinator owns:

- startup enumeration scheduling (`350 ms`);
- Android descriptor mapping into the pure USB model;
- attach classification;
- package-scoped USB permission requests and the `30_000 ms` timeout;
- a private permission receiver separated from the protected system detach
  receiver because the two broadcasts have different Android export semantics;
- the per-UI-entry startup gate while retaining process-scoped USB ownership;
- permission result rebinding through `UsbPermissionPolicy`;
- detach matching and pending-request removal;
- mode-switch polling (`16` attempts, `750 ms` interval);
- creation of `UsbSessionSnapshot` evidence after permission is available.

## Controlled platform-integration refinement

Stage 6A2 uses the approved controlled Android platform-integration amendment.
The legacy app remains the device/protocol behavioral reference, but deprecated
framework plumbing and accidental Activity-lifetime coupling are not copied when
current Android platform semantics or A2 ownership require a clean replacement.

For receiver delivery specifically:

- API 33+ permission callbacks use `RECEIVER_NOT_EXPORTED`;
- API 26-32 keep the pinned legacy dynamic permission-receiver registration,
  isolated behind the exact lint suppression for that compatibility branch;
- the permission PendingIntent remains package-scoped;
- `ACTION_USB_DEVICE_DETACHED` has its own system-only receiver and no export
  flag because it is a protected Android system broadcast.

For Activity delivery, creation and `onNewIntent` are intentionally distinct so
a normal new Intent cannot re-arm the startup scan. The UI only carries an opaque
entry-generation token used to prevent a stale `onDestroy` from cancelling a
replacement Activity's pending scan; it owns no USB descriptor, permission,
endpoint, reconnect, or transport state.

These framework choices do not constitute hardware validation. Permission,
detach, startup re-entry, and re-enumeration still require real-device evidence
before A2 can claim hardware compatibility.

### Platform-change record: receiver delivery

```text
Legacy mechanism:
One Activity-local dynamic receiver mixed the app-owned permission action with
ACTION_USB_DEVICE_DETACHED. API 33+ used RECEIVER_NOT_EXPORTED; API 26-32 used
the legacy two-argument registration.

Invariant preserved:
Permission results keep the same pending lookup, callback-device timeout
cancellation, rebinding, denial, and 30-second timeout semantics. Detach keeps
the same current-device matching and mode-switch policy.

Platform reason:
Target/compile SDK integration must express sender/export semantics explicitly
for the app-owned callback while treating the protected system detach broadcast
as a separate system-only responsibility.

Observable difference:
The two actions are registered on separate process-owned receivers. The pre-33
permission branch intentionally remains the pinned legacy mechanism.

Risk:
OEM/system broadcast delivery can differ from the legacy Activity-owned wiring.

Regression coverage:
Existing UsbPermissionPolicy and UsbSessionLifecyclePolicy tests remain the
behavioral contract; Android lint/build validate the framework API wiring.

Hardware validation:
NOT YET VERIFIED
```

### Platform-change record: Activity/startup lifetime

```text
Legacy mechanism:
startupUsbDiscoveryDone and its 350 ms callback lived in each MainActivity
instance; Activity destruction removed its pending Handler callbacks.

Invariant preserved:
One startup fallback scan is allowed per new Activity instance, normal
onNewIntent does not start that fallback, attach still cancels a pending startup
scan, and automatic connection still requires NONE/ERROR plus one unique
candidate.

Platform reason:
UsbSessionCoordinator must remain the sole Application-scoped USB owner without
turning MainActivity back into a USB state holder.

Observable difference:
The coordinator owns an opaque UI-entry generation. MainActivity only carries
that token so destruction can cancel its own pending startup callback. Permission
registry, current candidate, and an already-running mode-switch watch are not
moved back into Activity and may survive UI recreation.

Risk:
Lifecycle ordering could otherwise cancel a replacement Activity's scan or let
startup and mode-switch schedulers race. Generation matching prevents the first;
a unique startup candidate supersedes an older watch before access is requested.

Regression coverage:
The startup gate test covers one-shot scheduling and re-arming across UI-entry
generations. Existing attach/mode-switch tests preserve candidate and retry
semantics.

Hardware validation:
NOT YET VERIFIED
```

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
