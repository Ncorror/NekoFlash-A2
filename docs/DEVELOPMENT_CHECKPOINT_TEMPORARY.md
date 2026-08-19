# TEMPORARY DEVELOPMENT CHECKPOINT

> **Temporary development-only file.**
>
> This file exists so the active NekoFlash A2 migration state, hardware evidence,
> protected invariants, and next-step ordering are not lost between development
> sessions. It is not a release document and must not become the only source of
> truth for permanent behavior.
>
> **Removal rule:** delete this file before a stable/public release, but only after
> every durable invariant/evidence item that still matters has been moved into the
> permanent behavior contracts and validation documentation.

Last updated: **2026-08-19**

## 1. Source of truth and protected behavior

NekoFlash A2 is a clean implementation that preserves proven NekoFlash behavior.
The legacy repository remains the executable behavioral specification.

Pinned legacy baseline:

`Ncorror/NekoFlash@c49242c771dac9d147597c0d07e9ac1c6d320254`

Hardware-tested legacy release observed during the current A/B campaign:

`Ncorror/NekoFlash@a1493e2346b702af420f5b658354f76f073848a2`
(`6.0.0-alpha10.2`)

The USB/application implementation used by that release matches the pinned
baseline for the relevant USB transport files.

Protected unless separately proven and approved:

- USB attach/detach/re-enumeration lifecycle;
- USB permission-result semantics;
- ADB wire behavior;
- Fastboot wire behavior;
- Sideload classification;
- Mi Unlock behavior;
- destructive behavior;
- retry/recovery behavior.

Do not invent periodic USB retries, speculative recovery, vendor guesses, or
automatic transport reopen behavior.

Permanent USB behavior belongs in `docs/USB_BEHAVIOR_CONTRACT.md`.
This temporary file must not override that contract.

## 2. A2 identity and ownership

Final Android identity:

- package: `io.github.ncorror.nekoflash`
- namespace: `io.github.ncorror.nekoflash`
- applicationId: `io.github.ncorror.nekoflash`

Legacy package `ru.forum.adbfastboottool` must not return to A2 source,
namespace, or applicationId.

Ownership boundaries:

- `UsbSessionCoordinator` is the sole owner of USB discovery, permission,
  descriptor state, attach/detach, and re-enumeration;
- later, `OperationCoordinator` is the sole owner of active operation state,
  cancellation, progress, results, and operation diagnostics;
- `FlashOperationService` owns foreground-service lifecycle/notification only;
- UI must not open `UsbDeviceConnection`, claim interfaces, parse USB protocol
  packets, or own JNI/native transport state.

## 3. Approved entry-session boundary

Persisted risk acknowledgement is not USB session authorization.

A cold/full process entry starts unauthorized even if the current risk schema
was acknowledged previously.

Before the user explicitly continues through the entry gate, A2 must not start:

- startup USB enumeration;
- attach processing;
- `UsbManager.requestPermission`;
- auto-connect;
- reconnect automation;
- mode-switch automation.

An attach delivered before authorization is normalized and not replayed as a
stale attach payload. After authorization, A2 performs the normal conservative
`350 ms` enumeration against the descriptors that are present then.

Configuration recreation may preserve authorization.
Authorized root Back/backgrounding preserves the session, matching legacy
`moveTaskToBack(true)`.
Ordinary non-configuration UI-entry destruction revokes the current entry lease
while there is no operation-owned lease.

Broad shared-storage access is not an entry requirement.
Do not add `MANAGE_EXTERNAL_STORAGE` unless a later file workflow proves that
narrow user-selected access cannot preserve required semantics.

## 4. Welcome UI decision

The final Welcome screen will keep the NekoFlash visual identity and the proven
Welcome artwork.

The current Compose entry screen is a functional safety gate, not the final
visual design.

Final Welcome direction:

- keep the NekoFlash artwork/identity;
- keep explicit risk acknowledgement;
- require explicit Continue for each new process entry;
- RU + EN;
- do not restore the old broad-storage gate just for visual parity.

## 5. CI checkpoint

Last fully reviewed CI baseline before Stage 6A5A:

`b1298946b463202d54054ff8689192602f6f0134`
`finish diagnostics export verification`

Verified result:

- 101/101 unit tests passed;
- failures/errors/skipped: 0;
- Kotlin compiler warnings: 0;
- lint: 0 errors / 4 known baseline warnings;
- `testDebugUnitTest`: success;
- `lintDebug`: success;
- `assembleDebug`: success.

Current development head when this checkpoint was prepared:

`55d1ff7629cf446ef5294fd689d5061b72e7f390`
`pin USB observation and manual scan contract`

Stage: **6A5A — pure USB observation/manual-scan contract**

CI status at checkpoint creation: **PENDING REVIEW**.

Do not replace that with PASS until the reports artifact is inspected.

Expected Stage 6A5A total, if all new tests execute as intended: **112 tests**.
This number is an expectation, not evidence.

## 6. Reference A2 APK used for the first hardware campaign

Reference commit:

`b1298946b463202d54054ff8689192602f6f0134`

Reference debug APK SHA-256:

`6d872bc50109eef23d4b61832724737f4c53ede1c4afe1c8285525b5c4310890`

Keep this binary identity attached to all evidence collected with that APK.

## 7. Diagnostics checkpoint

Diagnostics are isolated per process run under an app-specific run directory.

Export schema:

`io.github.ncorror.nekoflash.usb-diagnostics-export.v1`

The Android export path uses user-selected document creation rather than broad
storage permission.

Real-device host-only preflight already proved:

- entry gate launches;
- coordinator starts only after authorization;
- `350 ms` startup enumeration runs;
- zero attached devices produce `devices=0`;
- no false USB permission request occurs;
- no false USB candidate occurs;
- system document creation works;
- exported ZIP opens and contains a valid manifest and event log.

## 8. Hardware setup currently used as evidence

Host:

- POCO F7
- Xiaomi model `25053PC47G`
- device `onyx`
- Android 16 / SDK 36

Patient:

- POCO X3 Pro
- `vayu`

Observed canonical ADB USB profile:

- VID:PID `18D1:4EE7`
- interface index `0`
- class/subclass/protocol `FF/42/01`
- bulk OUT `0x01`
- bulk IN `0x81`
- max packet size `512`

Hardware-proven connection signal from the legacy test setup:

**the patient charges from the NekoFlash host**.

Treat this as a positive proven signal of the known-good host/patient setup.
Do not dismiss it as irrelevant power-only behavior during this migration.

## 9. A2 hardware discovery evidence

Control diagnostics artifact:

`NekoFlash-A2-diagnostics-20260818-203856Z.zip`

SHA-256:

`4a01ca60e61ba1cd6afab70d7492b898d9d602b13e9a49dd9b121ade27630c96`

This run contains five USB session snapshots.

Observed result:

- 5/5 `USB_ATTACHED`;
- 5/5 `USB_CANDIDATE_READY`;
- all five selected the same POCO X3 Pro canonical ADB profile;
- no `USB_CANDIDATE_DISAPPEARED`;
- no `USB_ATTACHED_NO_CANDIDATE`;
- candidate remains `transport=not-opened`.

Measured `USB_ATTACHED -> USB_CANDIDATE_READY` latency was approximately:

- 9.2 ms
- 5.4 ms
- 4.8 ms
- 5.2 ms
- 10.8 ms

Average: approximately **7.1 ms**.

Current hardware conclusion for this layer:

- Android descriptor mapping: PASS;
- canonical ADB descriptor classification: PASS;
- A2 attach-to-candidate path: PASS in the 5/5 control run;
- detach classification: observed working;
- mode-switch watch lifecycle: observed working;
- actual USB transport: not implemented at this checkpoint.

Do not generalize this into “A2 flashing is hardware verified”.
Only the tested discovery/lifecycle layer has evidence.

## 10. Important A/B test caveat: legacy and A2 can compete for attach

Legacy and A2 have different application IDs, so both can be installed together.
Both also advertise a broad vendor-class `USB_DEVICE_ATTACHED` route.

During A/B testing Android displayed a chooser containing both NekoFlash apps.
That means a physical attach may be delivered to either selected handler.

Interpret logs accordingly:

- A2 can receive `USB_DETACHED current=false` for a device whose preceding attach
  was handled by legacy;
- missing A2 attach while legacy was selected is not evidence that A2 descriptor
  discovery failed;
- clean A2 attach tests must explicitly select A2 in the Android chooser or
  otherwise isolate the handler.

This interference explained a significant part of the earlier apparently
unstable attach evidence.

## 11. What legacy proves beyond A2's current stage

Legacy does not stop at descriptor detection.

Proven legacy ADB sequence:

`candidate -> permission -> openDevice -> claimInterface -> CNXN -> AUTH -> RSA ->
ADB banner -> packet dispatcher -> CONNECTED`

The A/B campaign observed the real RSA authorization prompt on the patient.
After authorization, subsequent legacy ADB connections were effectively immediate
at the timestamp resolution used by the legacy logs.

Legacy diagnostic evidence recorded no errors in that session and an
`adb.connect` milestone of about `15 ms` for the last connection.

A2 currently stops earlier:

`candidate -> Android permission -> CANDIDATE_READY -> transport=not-opened`

Therefore `CANDIDATE_READY` must never be presented as a real ADB/Fastboot
protocol connection.

## 12. Critical future ADB transport invariant

When Stage 6B begins, preserve the hardware-proven legacy rule:

**one transport, one CNXN**

Do not introduce automatic:

`close -> reopen -> second CNXN`

Legacy records that close/reopen plus another CNXN caused detach/attach cycling
on some Android USB hosts.

The first A2 ADB transport migration should be as mechanical as possible:

`openDevice -> claimInterface -> single CNXN -> AUTH -> banner -> single-reader dispatcher`

No speculative retry/recovery should be added during that migration.

## 13. Confirmed parity gaps before transport migration

### 13.1 Home observation is not wired yet

At the checkpoint base, `MainActivity` still provides a default `HomeUiState()`.
Therefore the UI may show:

- device not connected;
- USB disconnected;
- mode unknown;

while `UsbSessionCoordinator` already owns a real `CANDIDATE_READY`.

This is an observation/UI parity gap, not evidence of failed descriptor detection.

### 13.2 Legacy manual Search is missing from the Android runtime

Legacy explicit Search performs one fresh `UsbManager.deviceList` enumeration.

That recovery path is proven behavior and must return as an explicit user action.
It must not be replaced by a new periodic/background retry loop.

Legacy manual Search semantics:

- zero compatible candidates: report no compatible candidate;
- one canonical/compatible candidate: continue through the existing access path;
- multiple candidates: require explicit user selection;
- generic Fastboot candidate: require an extra explicit confirmation;
- one temporarily empty manual enumeration does not tear down an already-current
  USB generation; detach remains the invalidation boundary.

## 14. Stage 6A5A contract now pinned in source

The new pure observation states are:

- `INACTIVE`
- `SCANNING`
- `NO_DEVICE`
- `UNSUPPORTED_DEVICE`
- `MULTIPLE_CANDIDATES`
- `PERMISSION_PENDING`
- `PERMISSION_DENIED`
- `PERMISSION_ERROR`
- `CANDIDATE_READY`

`CANDIDATE_READY` means:

- descriptor selected;
- Android permission ready;
- enough information exists for the future transport layer;

and explicitly does **not** mean:

- `UsbDeviceConnection` opened;
- interface claimed;
- ADB handshake complete;
- Fastboot handshake complete.

UI-safe candidate observation contains only:

- stable key;
- display label;
- observed mode (`ADB` / `FASTBOOT`);
- interface index.

Android `UsbDevice` objects and transport state must not leak into Compose UI.

The pure manual-scan decisions currently pinned include:

- `NoCandidate`
- `PreserveCurrent`
- `Select`
- `ConfirmGenericFastboot`
- `Choose`

## 15. Next stage after Stage 6A5A CI is proven green

Stage **6A5B — Android USB observation wiring**.

Intended scope:

- expose read-only coordinator observation to UI;
- make Home reflect real coordinator state;
- add one explicit `Refresh/Search USB` action;
- perform exactly one fresh device-list enumeration per user action;
- preserve multi-device chooser behavior;
- preserve generic-Fastboot extra confirmation;
- revalidate user selection against current USB state before access;
- clear Home observation on a matching detach;
- keep diagnostics aligned with UI-visible state.

Explicitly out of scope for 6A5B:

- `UsbDeviceConnection.open`;
- interface claim;
- ADB CNXN/AUTH;
- Fastboot handshake;
- background retry loops;
- destructive operations.

After green CI, repeat a short hardware campaign on the same F7 -> X3 Pro setup:

- attach -> Home reflects the detected patient;
- detach -> Home clears;
- patient already attached -> explicit Refresh discovers it;
- repeated reconnect -> UI state and diagnostics agree.

## 16. Stage after observation parity: ADB transport

Only after Stage 6A5B is CI-green and hardware-checked should A2 begin the real
ADB transport migration.

First hardware validation for ADB transport must stay read-only.

Examples:

- `get-state`
- `getprop`
- `id`

No flashing/destructive work at this point.

## 17. Fastboot and destructive ordering

After read-only ADB transport is proven, bring up Fastboot read-only behavior.

Initial read-only Fastboot probes should stay within known behavior, such as:

- `getvar:product`
- `getvar:current-slot`
- `getvar:slot-count`
- `getvar:unlocked`
- `getvar:max-download-size`

A generic/compatible Fastboot descriptor match is only a candidate until a real
Fastboot protocol exchange confirms the peer.

Destructive validation remains last:

`descriptor -> permission -> lifecycle -> ADB read-only -> Fastboot read-only ->
re-enumeration -> cancellation/fail-closed -> destructive last`

Do not run flash/erase/unlock merely to accelerate validation.

## 18. Evidence vocabulary

Use these terms precisely:

- **CI-verified**: backed by reviewed CI reports for the exact commit;
- **hardware-observed**: seen in real-device diagnostics/screenshots;
- **hardware PASS**: only for the exact tested invariant/campaign;
- **NOT YET VERIFIED**: anything beyond the evidence boundary;
- **NOT IMPLEMENTED**: code path does not exist yet.

Never turn descriptor-level PASS into transport-level PASS.

## 19. Update protocol for this temporary file

After every meaningful stage:

1. update the active commit;
2. record the reviewed CI result only after reports are inspected;
3. add hardware evidence only after logs/ZIP/screenshots are reviewed;
4. move stable behavior rules into the permanent contract documents;
5. keep this file as a concise recovery checkpoint, not a second architecture spec.

If this file conflicts with a permanent contract, stop and resolve the conflict.
Do not silently choose whichever text is more convenient.

## 20. Removal checklist

Delete this temporary file when all of the following are true:

- all durable entry/USB/transport rules have permanent homes;
- hardware validation records have a durable final location;
- no active migration decision exists only here;
- the project no longer needs this file to reconstruct current development state;
- deletion happens before the stable/public release boundary.

Until then, update it deliberately rather than letting it drift.
