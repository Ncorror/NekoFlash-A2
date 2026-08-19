# Stage 6A5 - USB observation and explicit rescan parity

Baseline A2 commit: `b1298946b463202d54054ff8689192602f6f0134`.
Legacy behavior baseline: `c49242c771dac9d147597c0d07e9ac1c6d320254`.

Stage 6A5 is intentionally split:

- **6A5A** freezes the pure observation/manual-scan contract and regression tests;
- **6A5B** wires that contract into `UsbSessionCoordinator`, `MainActivity`, and
  the Home UI after 6A5A CI is green.

Stage 6A5B implementation commit:
`828d6dab289d5c7715e15d604a50fa17af73bcff`.

Stage 6A5B is CI-verified and hardware-verified for the observation/manual-rescan
scope recorded below. This split prevented a UI/lifecycle change from being mixed
with an untested USB selection rule.

## Why this stage exists

Hardware A/B testing showed that the A2 descriptor layer was stable when Android
delivered the USB attach to A2, while the pre-6A5B Home screen still rendered a
constant empty `HomeUiState`. A valid coordinator candidate could therefore look
visually disconnected.

The same comparison confirmed a legacy recovery path that pre-6A5B A2 had not yet
restored: the user-facing Search action performs one fresh `UsbManager.deviceList`
enumeration. It is not an automatic retry loop. Legacy cancels stale startup and
mode-switch callbacks, inspects the current inventory once, advances one candidate,
or requires user choice when several candidates are present. Stage 6A5B restores
that explicit user-facing path within the contract below.

## Hardware evidence before 6A5

Host:

- Xiaomi `25053PC47G` / `onyx`
- Android 16 / SDK 36

Target:

- POCO X3 Pro / `vayu`
- normal Android ADB device mode
- target charging from the NekoFlash host, matching the pinned legacy expected
  host/power direction

A2 commit `b1298946...` received five consecutive attach cycles when Android's USB
chooser was directed to A2. Every cycle produced the same candidate:

- VID:PID `18D1:4EE7`
- mode `ADB`
- match `CANONICAL`
- interface `0`
- class/subclass/protocol `FF/42/01`
- bulk OUT `0x01`
- bulk IN `0x81`
- max packet `512`

Observed `USB_ATTACHED -> USB_CANDIDATE_READY` latency was approximately
`4.1-10.8 ms`, with no `USB_CANDIDATE_DISAPPEARED` or
`USB_ATTACHED_NO_CANDIDATE` in the five-cycle control run.

The legacy application produced the same descriptor profile and then continued
into `openDevice`, interface claim, ADB `CNXN/AUTH`, RSA authorization, banner, and
single-reader dispatcher. A2 deliberately stops before that transport boundary.

## Observation contract

`UsbSessionCoordinator` remains the sole owner of USB discovery and permission
state. UI may receive only immutable observation snapshots. No `UsbDevice`, raw
endpoint object, permission registry, or Android USB object crosses into Compose.

The UI-safe candidate summary is intentionally small: stable key, display label,
observed mode, and interface index. VID/PID, endpoint objects, and protocol state
remain coordinator/diagnostics concerns.

A `CANDIDATE_READY` observation means:

- descriptor classification succeeded;
- Android USB permission is available for that candidate;
- diagnostic snapshot capture completed;
- transport is still **not opened**.

Home must therefore say that the device is detected, not that ADB/Fastboot is
connected. Actual connection wording is reserved for the later transport stage.

Activity recreation may replace the listener, but listener replacement must not
change USB ownership or coordinator state. The replacement UI must receive the
current immutable observation rather than reconstructing USB state itself.

## Explicit manual scan contract

One press on `Refresh USB` performs exactly one current inventory read.

Before that read A2 will cancel only the pending startup scan and active mode-switch
watch, matching the legacy explicit Search boundary. It must not create a timer,
repeat the scan, or introduce automatic recovery.

The pure decision rules frozen in 6A5A are:

- zero compatible candidates: report the current physical inventory only;
- one compatible non-generic candidate: advance that candidate through the existing
  permission policy with `automatic=false`;
- one generic Fastboot fallback candidate: require the same explicit warning/
  confirmation that legacy `connectManualCandidate` required before permission;
- several compatible candidates: do not auto-select, even if one is already the
  current candidate; require explicit user choice as legacy Search did, and a
  subsequently selected generic Fastboot fallback still requires its confirmation;
- one sole non-generic candidate advances through the existing permission/access
  path even when its stable key matches the current candidate; duplicate handling
  remains the coordinator's existing responsibility, matching legacy Search;
- one temporarily empty manual enumeration does not invalidate an existing current
  generation; detach remains the owner of invalidation.

6A5B additionally revalidates a chooser selection against a **fresh**
device-list snapshot before permission handling, so a stale UI choice cannot inject
an old descriptor into the coordinator.

## 6A5B implementation notes

The 6A5B Home renders only state that has a real source at this stage: detected
device label, observed USB mode, and coordinator USB state. Final Home fields such
as slot, topology, unlock state, and active operation are intentionally not rendered
until their real protocol/operation sources exist. This preserves the master prompt
rule that production UI must not contain placeholder state.

When manual Search finds no compatible candidate, A2 records the physical inventory
from the same single `UsbManager.deviceList` snapshot used for the decision. It does
not perform a second troubleshooting enumeration. This preserves the legacy
troubleshooting evidence while keeping the Stage 6A5 one-read contract.

### Legacy parity record: explicit Search

Compared against pinned legacy baseline `c49242c771dac9d147597c0d07e9ac1c6d320254`,
`MainActivity.scanForDevices`, `showUsbDeviceChooser`, `connectManualCandidate`, and
`requestUsbAccess` remain the executable reference for this user action:

- Search cancels pending startup discovery and the active mode-switch watch;
- Search enumerates the currently visible USB inventory and classifies with generic
  Fastboot fallback enabled;
- zero compatible candidates report a failure plus the physical USB inventory without
  disconnecting an existing generation;
- one non-generic candidate advances through the existing access/permission path with
  `automatic=false`;
- one generic Fastboot candidate requires explicit user confirmation before that same
  access/permission path;
- multiple candidates require explicit user choice and never auto-select one merely
  because it is already current;
- chooser-selected generic Fastboot still requires the same confirmation boundary;
- permission handling remains the already-migrated A2 permission policy, which preserves
  the legacy callback rebind-by-device behavior.

Intentional observable differences are limited to behavior already frozen by this Stage
contract and the master prompt:

- legacy troubleshooting logging performs another `deviceList` read; 6A5 requires one
  read per Refresh press, so A2 records equivalent inventory evidence from that same
  snapshot instead of enumerating twice;
- legacy dialogs render a broader descriptor subtitle; Compose receives only the
  UI-safe candidate summary frozen above, while the warning meaning and explicit
  confirmation remain unchanged.

No legacy transport step is migrated here. After permission and snapshot capture A2
still stops at `CANDIDATE_READY` with `transport=not-opened`.

### Platform-change record: Activity observation lifetime

```text
Legacy mechanism:
USB/connection presentation was rebuilt from Activity/ViewModel observers, and
Activity-local dialog/presentation state disappeared with that Activity instance.

Invariant preserved:
UsbSessionCoordinator remains the sole USB owner. Activity recreation does not
re-enumerate USB to reconstruct current descriptor/permission state, does not open
transport, and cannot cancel a replacement Activity's observer. Manual Search still
requires an explicit user action and preserves the legacy selection/confirmation
boundary.

Platform reason:
A2 requires Application-scoped USB ownership while Compose needs an immutable
current snapshot after Activity recreation. The UI therefore observes, but does not
own or reconstruct, coordinator state.

Observable difference:
A replacement Activity immediately receives the coordinator's current immutable
USB observation. Final protocol-derived Home fields are omitted until real sources
exist instead of rendering permanent placeholder values.

Risk:
Lifecycle ordering could let an old Activity clear a newer observer or let UI state
drift from coordinator ownership. The observer generation guard prevents stale
listener removal; the UI receives only immutable summaries.

Regression coverage:
UsbSessionObservationStore tests cover immediate snapshot delivery, replacement,
generation-guarded clearing, and state retention. Manual-scan policy tests cover
fresh chooser revalidation and generic Fastboot confirmation routing.

Hardware validation:
PASS for exact implementation commit
`828d6dab289d5c7715e15d604a50fa17af73bcff`.

Reviewed post-CI evidence:
- exact GitHub Actions run `32290292423` succeeded;
- 120/120 unit tests passed, with zero failures/errors/skips;
- lint remained 0 errors / 4 known baseline warnings;
- exact-run APK SHA-256:
  `ca29e88473da9c9d8b3636435588b06a7fb75caafd52045dfad54a492e308f14`;
- diagnostics `NekoFlash-A2-diagnostics-20260819-193354Z.zip`, SHA-256
  `b8f9da9666b616d5428285c5533dfe922d7152c6602e974d55539c33b9d2ab55`;
- Home visibly showed POCO X3 Pro / ADB / USB detected in portrait and landscape;
- Activity recreation preserved the coordinator-owned candidate observation;
- explicit Refresh observed the already-present single target;
- matching detach cleared the stale Home candidate;
- reattach returned to `CANDIDATE_READY`;
- all reviewed ready events remained `transport=not-opened`.

This PASS is limited to Stage 6A5B observation/manual-rescan/lifecycle invariants.
It does not verify ADB or Fastboot transport.
```

## Out of scope

Neither 6A5A nor 6A5B adds:

- periodic USB polling;
- background retry/recovery;
- new attach receivers;
- `UsbDeviceConnection` opening;
- interface claim;
- ADB `CNXN/AUTH`;
- Fastboot handshake;
- flashing, sideload, unlock, or destructive operations.

The proven legacy transport rule remains pending for Stage 6B: one transport, one
ADB `CNXN`; no automatic close/reopen and no repeated `CNXN` recovery loop.

## Verification gates

6A5A requires:

- pure manual-scan policy tests pass;
- pure Kotlin compiles with warnings treated as errors;
- normal project unit tests, lint, and `assembleDebug` remain green.

6A5B verification result for exact implementation commit
`828d6dab289d5c7715e15d604a50fa17af73bcff`:

- exact EN/RU resource-key parity: PASS;
- Kotlin compiler warnings observed in captured CI logs: 0;
- lint: 0 errors / 4 existing baseline warnings;
- `assembleDebug`: PASS;
- 120/120 unit tests: PASS;
- post-CI hardware retest: PASS for Home observation, explicit Refresh on an
  already-present single target, Activity recreation continuity, matching detach
  clearing, and reattach.

Stage 6A5B hardware status is therefore `PASS` for those exact invariants.
Transport remains `NOT IMPLEMENTED`.
The pre-change A2 descriptor attach path retains the five-cycle evidence recorded above.
